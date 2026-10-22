# Timescale + PostGIS docker image
FROM timescale/timescaledb-ha:pg16.4-ts2.17.0-all

USER root

# Add dependencies to install Apache AGE
RUN apt-get update && \
    apt-get install -y \
    build-essential \
    libreadline-dev \
    zlib1g-dev \
    flex \
    bison \
    postgresql-16-postgis-3 \
    postgresql-16-postgis-3-scripts \
    postgresql-server-dev-16 \
    git \
    cmake \
    libpq-dev

RUN useradd -m -s /bin/bash timescale

# Clone and install Apache AGE
RUN git clone --depth 1 --branch master https://github.com/apache/age.git && \
    cd age && \
    make install

# Turn on age and timescale extensions
RUN echo "shared_preload_libraries = 'timescaledb,age,postgis'" >> /usr/share/postgresql/postgresql.conf.sample

USER postgres

CMD ["postgres"]
