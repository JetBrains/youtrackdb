{
    if [ -d "/mnt/cache" ]; then
        # Define cache directories
        MAVEN_ARTIFACTS="/mnt/cache/maven"
        UBUNTU_HOME="/home/ubuntu"

        # Create local and volume cache directories
        mkdir -p "$MAVEN_ARTIFACTS" "${UBUNTU_HOME}/.m2/repository"

        # Bind mount volume-backed directories
        mount --bind "$MAVEN_ARTIFACTS" "${UBUNTU_HOME}/.m2/repository"

        # Ensure ubuntu user owns the directory
        chown -R ubuntu:ubuntu "${UBUNTU_HOME}/.m2"

        echo "Maven cache directories mounted from volume:"
        echo "  - $MAVEN_ARTIFACTS → ${UBUNTU_HOME}/.m2/repository"
    else
        echo "No maven cache volume available, proceeding without caching"
    fi
}
