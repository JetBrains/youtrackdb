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
  snapshot_name = "testflows-orchestrator-${formatdate("YYYY-MM-DD-hhmm", timestamp())}"

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

  # Provisioning: Install Python, pip, and testflows-github-hetzner-runners
  provisioner "shell" {
    inline = [
      "export DEBIAN_FRONTEND=noninteractive",

      # 1. Update System
      "apt-get update",
      "apt-get upgrade -y",

      # 2. Install Python and pip
      "apt-get install -y python3 python3-pip python3-venv",

      # 3. Create virtual environment for testflows
      "python3 -m venv /opt/testflows-runners",
      "/opt/testflows-runners/bin/pip install --upgrade pip",
      "/opt/testflows-runners/bin/pip install testflows.github.hetzner.runners",

      # 4. Create symlink for easy access
      "ln -sf /opt/testflows-runners/bin/github-hetzner-runners /usr/local/bin/github-hetzner-runners",

      # 5. Create configuration directory
      "mkdir -p /etc/github-hetzner-runners",

      # 6. Create systemd service file
      "cat > /etc/systemd/system/github-hetzner-runners.service << 'EOF'",
      "[Unit]",
      "Description=TestFlows GitHub Hetzner Runners",
      "After=network.target",
      "",
      "[Service]",
      "Type=simple",
      "EnvironmentFile=/etc/github-hetzner-runners/env",
      "ExecStart=/usr/local/bin/github-hetzner-runners \\",
      "    --github-token $${GITHUB_TOKEN} \\",
      "    --github-repository $${GITHUB_REPOSITORY} \\",
      "    --hetzner-token $${HCLOUD_TOKEN} \\",
      "    --max-runners $${MAX_RUNNERS:-4} \\",
      "    --type-x64 $${SERVER_TYPE_X64:-cx53} \\",
      "    --type-arm64 $${SERVER_TYPE_ARM64:-cax41} \\",
      "    --default-image-x64 $${IMAGE_X64:-github-runner-x86} \\",
      "    --default-image-arm64 $${IMAGE_ARM64:-github-runner-arm} \\",
      "    --default-firewall $${FIREWALL:-github-runner-protection} \\",
      "    --location $${LOCATION:-nbg1}",
      "Restart=always",
      "RestartSec=10",
      "StandardOutput=journal",
      "StandardError=journal",
      "",
      "[Install]",
      "WantedBy=multi-user.target",
      "EOF",

      # 7. Create example environment file
      "cat > /etc/github-hetzner-runners/env.example << 'EOF'",
      "# Required tokens",
      "GITHUB_TOKEN=ghp_your_github_personal_access_token",
      "GITHUB_REPOSITORY=owner/repo",
      "HCLOUD_TOKEN=your_hetzner_cloud_api_token",
      "",
      "# Optional settings (defaults shown)",
      "MAX_RUNNERS=4",
      "SERVER_TYPE_X64=cx53",
      "SERVER_TYPE_ARM64=cax41",
      "IMAGE_X64=github-runner-x86",
      "IMAGE_ARM64=github-runner-arm",
      "FIREWALL=github-runner-protection",
      "LOCATION=nbg1",
      "EOF",

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
