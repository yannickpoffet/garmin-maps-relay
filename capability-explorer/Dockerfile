# Ubuntu 22.04 is the last release carrying webkit2gtk-4.0, which the Connect IQ
# simulator links against. The host (Ubuntu 25.04) only ships 4.1, which is the
# whole reason this container exists.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        libwebkit2gtk-4.0-37 \
        libgtk-3-0 \
        libglib2.0-0 \
        libsecret-1-0 \
        libusb-1.0-0 \
        libgles2 \
        libatk-bridge2.0-0 \
        libatspi2.0-0 \
        libsm6 libice6 \
        fonts-dejavu-core \
        ca-certificates curl unzip openssl \
    && rm -rf /var/lib/apt/lists/*

# Run as the invoking user so files written to the mounted project and to
# ~/.Garmin stay owned by the host user rather than root.
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} dev 2>/dev/null || true \
 && useradd -m -u ${UID} -g ${GID} -s /bin/bash dev 2>/dev/null || true

ENV HOME=/home/dev
ENV PATH="/home/dev/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0/bin:${PATH}"

USER ${UID}:${GID}
WORKDIR /project
CMD ["/bin/bash"]
