# Judge0 1.13.1 with isolate 2.2 instead of the bundled isolate 1.8.1.
#
# isolate 1.8.1 only speaks cgroup v1. Docker Desktop (WSL2) and most current
# Linux hosts run cgroup v2, where every submission then fails with
# "Internal Error" (Failed to create control group /sys/fs/cgroup/memory/box-N/).
# isolate 2.x supports cgroup v2; v2.2 is the newest release that needs only
# libcap, which this Debian 10 base can build.
FROM judge0/judge0:1.13.1

USER root

# Debian 10 (buster) is archived: its packages now live on archive.debian.org.
# The extra mono/nodesource lists are set aside so they can't break apt-get update.
# Its kernel headers predate quotactl_fd (syscall 443, kernel 5.14+), so supply the number.
RUN mkdir /tmp/apt-lists && mv /etc/apt/sources.list.d/* /tmp/apt-lists/ && \
    printf 'deb http://archive.debian.org/debian buster main\ndeb http://archive.debian.org/debian-security buster/updates main\n' \
      > /etc/apt/sources.list && \
    apt-get -o Acquire::Check-Valid-Until=false update && \
    apt-get install -y --no-install-recommends git libcap-dev make && \
    git clone --depth 1 --branch v2.2 https://github.com/ioi/isolate.git /tmp/isolate && \
    make -C /tmp/isolate isolate CFLAGS_EXTRA=-DSYS_quotactl_fd=443 && \
    install -m 4755 /tmp/isolate/isolate /usr/local/bin/isolate.real && \
    rm /usr/local/bin/isolate && \
    # No autoremove: it would also strip packages the base image's compilers rely on (e.g. a JRE)
    apt-get purge -y git && \
    rm -rf /tmp/isolate /var/lib/apt/lists/* && \
    mv /tmp/apt-lists/* /etc/apt/sources.list.d/ && rmdir /tmp/apt-lists

COPY isolate.cf /usr/local/etc/isolate
COPY isolate-wrapper.sh /usr/local/bin/isolate
COPY cgroup-setup.sh /usr/local/bin/cgroup-setup.sh
COPY entrypoint.sh /api/codepulse-entrypoint.sh
RUN chmod 644 /usr/local/etc/isolate && \
    chmod 755 /usr/local/bin/isolate /usr/local/bin/cgroup-setup.sh /api/codepulse-entrypoint.sh

USER judge0

ENTRYPOINT ["/api/codepulse-entrypoint.sh"]
CMD ["/api/scripts/server"]
