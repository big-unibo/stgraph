# The environment of the notebooks of the evaluation (evaluation/postprocessing/STGraph.ipynb, evaluation/stats.ipynb), as
# the devcontainer of the repository STGraph-evaluation; built from the folder evaluation (see devcontainer.json)
FROM python:3.12-slim

COPY requirements.txt /tmp/requirements.txt

RUN apt-get update \
&& apt-get install gcc -y \
&& apt-get install git -y \
&& apt-get clean

RUN pip install --upgrade pip
RUN pip install -r /tmp/requirements.txt
RUN pip install flake8 black jupyter

ENV TZ="Europe/Rome"

RUN echo "deb http://deb.debian.org/debian bookworm contrib non-free" > /etc/apt/sources.list.d/contrib.list
RUN pip install ipykernel && python -m ipykernel install --user --name=devcontainer --display-name "Python (devcontainer)"

RUN apt-get update
RUN apt-get install -y fonts-dejavu
RUN apt-get install -y ttf-mscorefonts-installer
RUN apt-get install -y texlive-full
RUN rm -rf /var/lib/apt/lists/*

EXPOSE 8888
