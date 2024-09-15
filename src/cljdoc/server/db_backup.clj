(ns cljdoc.server.db-backup
  "Periodically backup SQLite database to S3 compatible storage.

  See `backup-retention` for how many backups we keep for each backup period.

  Naming scheme is: `<backup period>/<prefix><target-date>@<timestamp><ext>` where:
  - `<backup period>` are `daily`, `weekly`, `monthly`, `yearly`
  - `<prefix>` currently `cljdoc-db-`
  - `<target-date>` is `yyyy-MM-dd` for logical backup date
  - `<timestamp>` is `yyyy-MM-ddTHH:mm:ss` for actual date of backup
  - `<ext>` currently `.tar.zst`

  We chose zstd as our compression format. In testing performed much better than gz in
  compression speed, decompression speed and size.

  Missing `weekly`, `montly`, and `yearly` backups are always copied from available
  `daily` backups on a best fit basis. For example if there is no `yearly` backup for 2024
  we'll fill it with the best candidate from our `daily` backups. This is why you might see
  something like:

  `yearly/cljdoc-db/2024-01-01@2024-09-15T13:14:52.tar.zst`.

  In this case, the available daily backup from Sept 15th 2024 was our best fit."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cljdoc.server.log-init] ;; to quiet odd jetty DEBUG logging
            [cljdoc.util.sentry :as sentry]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as awscreds]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [tea-time.core :as tt])
  (:import (java.time DayOfWeek LocalDate LocalDateTime Period)
           (java.time.format DateTimeFormatter)
           (java.time.temporal TemporalAdjusters)
           (java.util.concurrent TimeUnit)
           (org.sqlite SQLiteConnection)
           (org.sqlite.core DB$ProgressObserver)))

(set! *warn-on-reflection* true)

(def ^:private backup-retention {:daily 7
                                 :weekly 4
                                 :monthly 12
                                 :yearly 2})

(defn- s3-client [{:keys [backups-bucket-region backups-bucket-key backups-bucket-secret]}]
  (aws/client {:api :s3
               ;; need a valid aws region (even though we are using Exoscale) to overcome bug
               ;; https://github.com/cognitect-labs/aws-api/issues/150
               :region "us-east-2"
               :credentials-provider (awscreds/basic-credentials-provider
                                      {:access-key-id backups-bucket-key
                                       :secret-access-key backups-bucket-secret})
               :endpoint-override {:protocol :https
                                   :hostname (format "sos-%s.exo.io" backups-bucket-region)
                                   :region backups-bucket-region}}))

(defn- s3-list->backups [{:keys [Contents]}]
  (->> Contents
       (mapv :Key)
       ;; TODO: consider warning on non-matches
       (keep #(re-matches #"(?x)                               # group 0: key
                              (daily|weekly|monthly|yearly)    # group 1: period
                              /
                              (.*)                             # group 2: prefix
                              (\d{4}-\d{2}-\d{2})              # group 3: target-date
                              @
                              (\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d) # group 4: timestamp
                              (.*)                             # group 5: extension"
                          %))
       (mapv #(zipmap [:key :period :prefix :target-date :timestamp :extension] %))
       (mapv #(update % :period keyword))
       (mapv #(update % :target-date (fn [s] (LocalDate/parse s))))))

(defn- existing-backups [s3 {:keys [backups-bucket-name aws-invoke-fn]}]
  (-> (aws-invoke-fn s3 {:op :ListObjectsV2 :request {:Bucket backups-bucket-name}})
      s3-list->backups))

(defn- get-backup-for [backup-list period ^LocalDate target-date]
  (some #(and (= period (:period %))
              (= target-date (:target-date %)))
        backup-list))

(defn- db-backup-filename [^LocalDateTime datetime]
  (format "cljdoc-db-%s@%s.tar.zst"
          (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd") datetime)
          (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss") datetime)))

(defn create-backup-tracker []
  (let [last-report-time (atom (System/currentTimeMillis))]
    (fn track-backup-status [{:keys [dbname total-pages remaining-pages]}]
      (let [time-now (System/currentTimeMillis)]
        (cond
          (zero? total-pages)
          (log/warnf "%s empty" dbname)

          (>= time-now (+ @last-report-time 1000))
          (do
            (reset! last-report-time time-now)
            (log/infof "%s backup %.2f%% complete" dbname (* 100 (/ (- total-pages remaining-pages) (float total-pages))))))))))

(defn- backup-sqlite-db! [{:keys [dbname] :as db-spec} dest-dir]
  ;; TODO: consider warning on missing dbs?
  (let [target (str (fs/file dest-dir (fs/file-name dbname)))]
    (log/infof "Backing up %s db to %s" dbname target)
    (with-open  [^SQLiteConnection conn (jdbc/get-connection db-spec)]
      (let [backup-tracker-fn (create-backup-tracker)]
        ;; TODO: check return, should be 0 for OK
        ;; https://www.sqlite.org/c3ref/c_abort.html
        (.backup (.getDatabase conn) "main" target
                 (reify DB$ProgressObserver
                   (progress [_ remaining-pages total-pages]
                     (backup-tracker-fn {:dbname dbname
                                         :total-pages total-pages
                                         :remaining-pages remaining-pages}))))
        (log/infof "%s backup complete" dbname)))))

(defn- backup-db!
  "Create compressed backup for `db-spec` and `cache-db-spec` to `dest-file`"
  [{:keys [db-spec cache-db-spec] :as _opts} dest-file]
  (fs/with-temp-dir [backup-work-dir {:prefix "cljdoc-db-backup-work"}]
    (backup-sqlite-db! db-spec backup-work-dir)
    (backup-sqlite-db! cache-db-spec backup-work-dir)
    (log/infof "Compressing backup to %s" dest-file)
    (process/shell {:dir backup-work-dir} "tar --use-compress-program=zstd -cf" dest-file ".")))

(comment
  (def db-spec {:dbtype "sqlite"
                :host :none
                :dbname "data/cljdoc.db.sqlite"})

  (backup-sqlite-db! db-spec "target")

  (jdbc/execute!
   db-spec
   ["create table sample(id, name)"])

  (jdbc/execute! db-spec ["backup backup.db"])

  (with-open [conn (jdbc/get-connection db-spec)]
    (try
      (let [stmt (.createStatement conn)]
        (.execute stmt "backup to 'backup.db'")
        (println "Backup completed successfully."))
      (catch Exception e
        (println "Backup failed:" (.getMessage e)))))

  (with-open [conn (jdbc/get-connection db-spec)]
    (let [conn (cast SQLiteConnection conn)]
      (.backup conn "target/foo.db" nil nil))))

(defn- store-daily-backup!
  [s3 {:keys [backups-bucket-name aws-invoke-fn]} backup-file]
  (let [target-key (str "daily/" (fs/file-name backup-file))]
    (log/infof "Storing %s" target-key)
    (with-open [input-stream (io/input-stream backup-file)]
      (aws-invoke-fn s3 {:op :PutObject
                         :request {:Bucket backups-bucket-name
                                   :Key target-key
                                   :Body input-stream
                                   :ACL "public-read"}}))
    (log/infof "Storing complete for %s" target-key)))

(defn- ideal-backups [^LocalDateTime datetime]
  (let [date (.toLocalDate datetime)
        backup-calcs {:daily   {:start-fn identity
                                :period (Period/ofDays 1)}
                      :weekly  {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))
                                :period (Period/ofWeeks 1)}
                      :monthly {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/firstDayOfMonth)))
                                :period (Period/ofMonths 1)}
                      :yearly  {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/firstDayOfYear)))
                                :period (Period/ofYears 1)}}]
    (->> (for [period-key [:daily :weekly :monthly :yearly]
               :let [{:keys [start-fn ^Period period]} (period-key backup-calcs)
                     count (period-key backup-retention)]]
           (->> (iterate (fn [^LocalDate d] (.minus d period)) (start-fn date))
                (take count)
                (mapv (fn [^LocalDate d] {:period period-key :target-date d :max-date (.plus d period)}))))
         (mapcat identity))))

(defn- fillable-backups [existing-backups ideal-backups]
  (let [missing-backups (let [existing-keys (->> existing-backups
                                                 (mapv #(select-keys % [:period :target-date]))
                                                 set)]
                          (remove #(contains? existing-keys (select-keys % [:period :target-date])) ideal-backups))
        daily-backups (->> existing-backups
                           (filterv #(= :daily (:period %)))
                           (sort-by :target-date))]
    (reduce (fn [acc {:keys [period ^LocalDate target-date max-date] :as missing}]
              (if-let [daily-match (some (fn [daily]
                                           (let [^LocalDate daily-target-date (:target-date daily)]
                                             (when (and (or (.isEqual target-date daily-target-date)
                                                            (.isAfter daily-target-date target-date))
                                                        (.isBefore daily-target-date max-date))
                                               daily)))
                                         daily-backups)]
                (conj acc (assoc missing :daily-match daily-match))
                acc))
            []
            missing-backups)))

(defn- fill-backup [s3 {:keys [backups-bucket-name aws-invoke-fn]} source dest]
  (log/infof "Filling %s from %s" dest source)
  (aws-invoke-fn s3 {:op :CopyObject
                     :request {:Bucket backups-bucket-name
                               :CopySource (str backups-bucket-name "/" source)
                               :Key dest}}))

(defn- fill-copy-list [fillable-backups]
  (into [] (for [{:keys [period target-date daily-match]} fillable-backups
                 :let [source (:key daily-match)
                       dest (format "%s/%s%s@%s%s"
                                    (name period)
                                    (:prefix daily-match)
                                    target-date
                                    (:timestamp daily-match)
                                    (:extension daily-match))]]
             [source dest])))

(defn- prunable-backups [existing-backups]
  (->> existing-backups
       (sort-by :timestamp)
       reverse
       (group-by :period)
       (mapcat (fn [[period backups]]
                 (let [keep-count (period backup-retention)]
                   (drop keep-count backups))))
       (into [])))

(defn- prune-backup! [s3 {:keys [backups-bucket-name aws-invoke-fn]} {:keys [key]}]
  (log/infof "Pruning %s" key)
  (aws-invoke-fn s3 {:op :DeleteObject
                     :request {:Bucket backups-bucket-name
                               :Key key}}))

(defn- daily-backup! [s3 opts ^LocalDateTime datetime]
  (let [existing (existing-backups s3 opts)]
    (when-not (get-backup-for existing :daily (.toLocalDate datetime))
      (fs/with-temp-dir [backup-file-dir {:prefix "cljdoc-db-backup"}]
        (let [backup-file (str (fs/file backup-file-dir (db-backup-filename datetime)))]
          (backup-db! opts backup-file)
          (store-daily-backup! s3 opts backup-file))))))

(defn- fill-missing-backups! [s3 opts ^LocalDateTime datetime]
  (let [existing (existing-backups s3 opts)
        ideal (ideal-backups datetime)
        fillable (fillable-backups existing ideal)]
    (doseq [[source dest] (fill-copy-list fillable)]
      (fill-backup s3 opts source dest))))

(defn- prune-old-backups! [s3 opts]
  (let [existing (existing-backups s3 opts)]
    (doseq [backup (prunable-backups existing)]
      (prune-backup! s3 opts backup))))

(defn backup-job! [{:keys [now-fn] :as opts}]
  (log/info "Backup job started")
  (let [s3 (s3-client opts)
        now (now-fn)]
    (daily-backup! s3 opts now)
    (fill-missing-backups! s3 opts now)
    (prune-old-backups! s3 opts))
  (log/info "Backup job complete"))

(defn- wrap-error [wrapped-fn]
  (fn []
    (try
      (wrapped-fn)
      (catch Exception e
        (log/error e)
        (sentry/capture {:ex e})))))

(defmethod ig/init-key :cljdoc/db-backup
  [k {:keys [enable-db-backup?] :as opts}]
  (if-not enable-db-backup?
    (log/info "Database backup disable, skipping " k)
    (do (log/info "Starting" k)
        {::db-backup-job (tt/every!
                           ;; we backup daily but check more often to cover failure cases
                          (.toSeconds TimeUnit/HOURS 2)
                           ;; wait 30 minutes to avoid overlap with blue/green deploy and other jobs
                          (.toSeconds TimeUnit/MINUTES 30)
                          (wrap-error #(backup-job! (assoc opts
                                                           :now-fn (fn [] (LocalDateTime/now))
                                                           :aws-invoke-fn aws/invoke))))})))

(defmethod ig/halt-key! :cljdoc/db-backup
  [k db-backup]
  (when db-backup
    (log/info "Stopping" k)
    (tt/cancel! (::db-backup-job db-backup))))

(comment
  (require '[cljdoc.config :as cfg])

  (def opts (assoc (cfg/backup (cfg/config)) :aws-invoke-fn aws/invoke))

  (:backups-bucket-region opts)
  ;; => "ch-gva-2"

  (def s3 (s3-client opts))

  (db-backup-filename (LocalDateTime/now))
  ;; => "cljdoc-db-2024-09-14@2024-09-14T20:36:55.tar.zst"

  (aws/invoke s3 {:op :PutObject :request {:Bucket "cljdoc-backups"
                                           :Key "daily/hello.txt"
                                           :Body (io/input-stream (.getBytes "Oh hai!"))}})

  (store-daily-backup! s3 opts "/home/lee/Downloads/graal-build-time-1.0.5.jar")

  :eoc)
