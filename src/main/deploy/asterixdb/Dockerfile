# Use the official OpenJDK 21 image (slim variant) as the base image
FROM openjdk:21-jdk-slim

# Set the maintainer label
LABEL maintainer="MP <manuele.pasini@unibo.it>"

# Set the timezone and install necessary dependencies
RUN apt-get update && apt-get install -y tzdata && \
    ln -fs /usr/share/zoneinfo/$TZ /etc/localtime && \
    apt-get install -y \
    git \
    maven \
    curl \
    python3 \
    unzip \
    python3-pip && \
    rm -rf /var/lib/apt/lists/*  # Clean up apt cache to reduce image size

WORKDIR /workspace

RUN curl -O https://downloads.apache.org/asterixdb/asterixdb-0.9.9/asterix-server-0.9.9-binary-assembly.zip

RUN unzip asterix-server-0.9.9-binary-assembly.zip && \
    rm asterix-server-0.9.9-binary-assembly.zip

WORKDIR /workspace/apache-asterixdb-0.9.9

# Set the default command to open a bash shell
CMD ["/bin/bash"]