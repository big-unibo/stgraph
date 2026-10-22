FROM python:3.12-slim

RUN mkdir -p /home
# Copy REQUIREMENT_FILE in src folder
COPY ./requirements.txt  /home/
COPY . /home

RUN apt-get update \
&& apt-get install gcc -y \
&& apt-get install git -y \
&& apt-get clean

RUN pip install --upgrade pip
RUN pip install -r /home/requirements.txt
RUN pip install flake8 black jupyter

# Set PYTHONPATH
ENV PYTHONPATH="/home/:$PATH"
ENV TZ="Europe/Rome"

RUN echo "deb http://deb.debian.org/debian bookworm contrib non-free" > /etc/apt/sources.list.d/contrib.list
RUN pip install ipykernel && python -m ipykernel install --user --name=devcontainer --display-name "Python (devcontainer)"

RUN apt-get update
RUN apt-get install -y fonts-dejavu
RUN apt-get install -y ttf-mscorefonts-installer
RUN apt-get install -y texlive-full
RUN rm -rf /var/lib/apt/lists/*

EXPOSE 8888