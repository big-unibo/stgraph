FROM hououou/aeong:v1

RUN source /opt/toolchain-v4/activate && \
    git clone https://github.com/ManuelePasini/mp4siniAeonG.git /home/AeonG && \
    wget -P /home/AeonG/libs https://github.com/memgraph/memgraph/raw/v2.2.0/libs/antlr4.patch && \
    wget -P /home/AeonG/libs https://github.com/memgraph/memgraph/raw/v2.2.0/libs/librdtsc.patch && \
    wget -P /home/AeonG/libs https://github.com/memgraph/memgraph/raw/v2.2.0/libs/rocksdb.patch && \
    git --git-dir=/home/AeonG/.git --work-tree=/home/AeonG checkout -- quicklisp.lisp

# Needed to download dataset from Google Drive
RUN pip3 install "gdown<4.7" "beautifulsoup4<4.11" numpy pandas

ENV LD_LIBRARY_PATH=/home/AeonG/libs/protobuf/lib:$LD_LIBRARY_PATH

WORKDIR /home/AeonG
RUN chmod +x tests/experiments/*.sh

RUN mkdir build

WORKDIR /home/AeonG/

RUN source /opt/toolchain-v4/activate && \
    ./init

WORKDIR /home/AeonG/build

RUN source /opt/toolchain-v4/activate && \
    cmake .. && \
    make -j$(nproc) memgraph

WORKDIR /home/AeonG/build/tests/mgbench

RUN source /opt/toolchain-v4/activate && \
    make

WORKDIR /home/AeonG/tests/experiments

RUN pip3 install numpy
RUN pip3 install pandas

CMD ["bash"]