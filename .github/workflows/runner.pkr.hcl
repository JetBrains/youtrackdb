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
# Source: x86_64 (Intel/AMD)
# ------------------------------------------------------------------------
source "hcloud" "runner-x86" {
  token         = var.hcloud_token
  image         = "ubuntu-24.04"
  location      = "nbg1"
  server_type = "cx53"
  ssh_username  = "root"
  snapshot_name = "github-runner-x86-${formatdate("YYYY-MM-DD-hhmm", timestamp())}"

  snapshot_labels = {
    role = "github-runner"
    arch = "x86"
    os   = "ubuntu-24.04"
  }
}

# ------------------------------------------------------------------------
# Source: ARM64
# ------------------------------------------------------------------------
source "hcloud" "runner-arm" {
  token         = var.hcloud_token
  image         = "ubuntu-24.04"
  location = "nbg1"
  server_type   = "cax41"
  ssh_username  = "root"
  snapshot_name = "github-runner-arm-${formatdate("YYYY-MM-DD-hhmm", timestamp())}"

  snapshot_labels = {
    role = "github-runner"
    arch = "arm"
    os   = "ubuntu-24.04"
  }
}

# ------------------------------------------------------------------------
# Build Block
# ------------------------------------------------------------------------
build {
  sources = [
    "source.hcloud.runner-x86",
    "source.hcloud.runner-arm"
  ]

  # Provisioning: Install Docker, Git, and Runner Dependencies
  provisioner "shell" {
    inline = [
      "export DEBIAN_FRONTEND=noninteractive",

      # 1. Update System
      "apt-get update",
      "apt-get upgrade -y",

      # 2. Install Runner Dependencies (Added jq, tar, unzip here)
      # 'jq' is crucial for the startup script to parse the GitHub API response
      "apt-get install -y git curl jq tar unzip apt-transport-https ca-certificates software-properties-common",

      # 3. Install Docker
      "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg",
      "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable\" | tee /etc/apt/sources.list.d/docker.list > /dev/null",
      "apt-get update",
      "apt-get install -y docker-ce docker-ce-cli containerd.io",

      # 4. Clean up apt cache to keep image small
      "apt-get clean",
      "rm -rf /var/lib/apt/lists/*",

      # 5. Verify installations
      "docker --version",
      "git --version",
      "jq --version"
    ]
  }
}