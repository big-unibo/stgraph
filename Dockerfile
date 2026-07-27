FROM gradle:8.3-jdk17

LABEL authors="manuele.pasini2"

COPY . /dt_graph
WORKDIR /dt_graph
RUN apt-get update && apt install nano
RUN gradle build  -x test --no-daemon

CMD ["bash"]