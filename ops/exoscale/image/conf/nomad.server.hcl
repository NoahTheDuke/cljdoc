server {
  enabled          = true
  bootstrap_expect = 1
}

client {
  enabled = true
}

plugin "docker" {
  config {
    volumes {
      enabled      = true
      selinuxlabel = "z"
    }
  }
}
