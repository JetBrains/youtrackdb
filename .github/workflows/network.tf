terraform {
  required_providers {
    hcloud = {
      source = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

variable "hcloud_token" {
  sensitive = true
}

provider "hcloud" {
  token = var.hcloud_token
}

# ------------------------------------------------------------------------
# Security: The Firewall
# ------------------------------------------------------------------------
resource "hcloud_firewall" "runner_firewall" {
  name = "github-runner-protection"

  # INBOUND: Block everything (Safety first)
  # GitHub runners communicate OUT to GitHub, so they don't need open ports.
  # We only allow ICMP (Ping) for debugging connectivity.
  rule {
    direction = "in"
    protocol  = "icmp"
    source_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }

  # OUTBOUND: Allow everything
  # The runner needs to fetch code, download Docker images, apt-get update, etc.
  rule {
    direction = "out"
    protocol  = "tcp"
    port      = "any"
    destination_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }

  rule {
    direction = "out"
    protocol  = "udp"
    port      = "any"
    destination_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }
}

# ------------------------------------------------------------------------
# Output: We need this ID for your GitHub Action
# ------------------------------------------------------------------------
output "firewall_name" {
  value = hcloud_firewall.runner_firewall.name
}