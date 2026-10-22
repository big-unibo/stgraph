FROM python:3.12-slim

RUN apt-get update && apt-get install -y wget nano
COPY requirements.txt .

RUN pip3 install -r requirements.txt

COPY /hygraph /hygraph

WORKDIR /hygraph