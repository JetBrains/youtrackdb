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

  # AUTO-APPLY: Automatically attach to servers with label role=github-runner
  # TestFlows creates runners with this label via --with-label option
  apply_to {
    label_selector = "role=github-runner"
  }

  # INBOUND: Allow SSH for TestFlows orchestrator to configure runners
  # Runners are ephemeral and protected by SSH key authentication
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "22"
    source_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }

  # INBOUND: Allow ICMP (Ping) for debugging connectivity
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
# Security: TestFlows Orchestrator Firewall
# ------------------------------------------------------------------------
resource "hcloud_firewall" "orchestrator_firewall" {
  name = "testflows-orchestrator-protection"

  # INBOUND: Allow SSH for automated updates from GitHub Actions
  rule {
    direction = "in"
    protocol  = "tcp"
    port      = "22"
    source_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }

  # INBOUND: Allow ICMP (Ping) for debugging connectivity
  rule {
    direction = "in"
    protocol  = "icmp"
    source_ips = [
      "0.0.0.0/0",
      "::/0"
    ]
  }

  # OUTBOUND: Allow everything
  # The orchestrator needs to communicate with GitHub API and Hetzner API
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
# Output: Firewall names for reference
# ------------------------------------------------------------------------
output "firewall_name" {
  value = hcloud_firewall.runner_firewall.name
}

output "orchestrator_firewall_name" {
  value = hcloud_firewall.orchestrator_firewall.name
}