FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

USER root

# Remove the broken Yarn APT repository inherited from the base image.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && rm -f /etc/apt/trusted.gpg.d/yarn.gpg \
    && rm -f /usr/share/keyrings/yarn-archive-keyring.gpg