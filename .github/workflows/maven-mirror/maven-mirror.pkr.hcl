packer {
  required_plugins {
    hcloud = {
      version = ">= 1.0.0"
      source  = "github.com/hetznercloud/hcloud"
    }
  }
}

variable "hcloud_token" {
  type      = string
  default   = "${env("HCLOUD_TOKEN")}"
  sensitive = true
}

variable "mirror_domain" {
  type        = string
  default     = "maven.youtrackdb.io"
  description = "Domain name for the Maven mirror (used for Caddy TLS certificate)"
}

source "hcloud" "maven-mirror" {
  token        = var.hcloud_token
  image        = "ubuntu-24.04"
  location = "nbg1"
  server_type = "cax11" # Cheapest ARM64 instance (use cx22 for Intel)
  ssh_username = "root"
  snapshot_name = "reposilite-mirror-v{{timestamp}}"

  # Label the snapshot for easy finding later
  snapshot_labels = {
    application = "reposilite"
    type        = "maven-mirror"
  }
}

build {
  sources = ["source.hcloud.maven-mirror"]

  # Upload the setup script to the server
  provisioner "file" {
    source      = "${path.root}/setup.sh"
    destination = "/tmp/setup.sh"
  }

  # Execute the setup script with the domain variable
  provisioner "shell" {
    inline = [
      "chmod +x /tmp/setup.sh",
      "/tmp/setup.sh ${var.mirror_domain}",
      "rm /tmp/setup.sh"
    ]
  }
}