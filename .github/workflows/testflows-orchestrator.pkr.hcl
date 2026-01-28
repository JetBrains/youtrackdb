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

# ------------------------------------------------------------------------
# Source: TestFlows Orchestrator (small x86 server)
# ------------------------------------------------------------------------
source "hcloud" "testflows-orchestrator" {
  token         = var.hcloud_token
  image         = "ubuntu-24.04"
  location      = "nbg1"
  server_type   = "cx23"
  ssh_username  = "root"
  snapshot_name = "testflows-orchestrator"

  snapshot_labels = {
    role = "testflows-orchestrator"
    os   = "ubuntu-24.04"
  }
}

# ------------------------------------------------------------------------
# Build Block
# ------------------------------------------------------------------------
build {
  sources = [
    "source.hcloud.testflows-orchestrator"
  ]

  # Create configuration directory before copying files
  provisioner "shell" {
    inline = [
      "mkdir -p /etc/github-hetzner-runners"
    ]
  }

  provisioner "file" {
    source      = "./testflows-orchestrator/github-hetzner-runners-wrapper"
    destination = "/usr/local/bin/github-hetzner-runners-wrapper"
  }

  provisioner "file" {
    source      = "./testflows-orchestrator/github-hetzner-runners.service"
    destination = "/etc/systemd/system/github-hetzner-runners.service"
  }

  provisioner "file" {
    source      = "./testflows-orchestrator/env.example"
    destination = "/etc/github-hetzner-runners/env.example"
  }

  provisioner "file" {
    source      = "./testflows-orchestrator/scripts"
    destination = "/etc/github-hetzner-runners/scripts"
  }

  # Install dependencies and configure the orchestrator
  provisioner "shell" {
    inline = [
      "export DEBIAN_FRONTEND=noninteractive",

      # 1. Update System
      "apt-get update",
      "apt-get upgrade -y",

      # 2. Install and configure UFW firewall
      "apt-get install -y ufw",
      "ufw --force reset",
      "ufw default deny incoming",
      "ufw default allow outgoing",
      "ufw allow 22/tcp",
      "ufw --force enable",
      "ufw status verbose",

      # 3. Install Python, pip, and SSH client
      "apt-get install -y python3 python3-pip python3-venv openssh-client",

      # 4. Create virtual environment for testflows
      "python3 -m venv /opt/testflows-runners",
      "/opt/testflows-runners/bin/pip install --upgrade pip",
      "/opt/testflows-runners/bin/pip install testflows.github.hetzner.runners",

      # 5. Create symlink for easy access
      "ln -sf /opt/testflows-runners/bin/github-hetzner-runners /usr/local/bin/github-hetzner-runners",

      # 6. Make wrapper script executable
      "chmod +x /usr/local/bin/github-hetzner-runners-wrapper",

      # 7. Make setup scripts executable
      "chmod +x /etc/github-hetzner-runners/scripts/*",

      # 8. Reload systemd
      "systemctl daemon-reload",

      # 9. Clean up apt cache
      "apt-get clean",
      "rm -rf /var/lib/apt/lists/*",

      # 10. Verify installation
      "github-hetzner-runners --help || echo 'TestFlows runners installed (help may require tokens)'"
    ]
  }
}
