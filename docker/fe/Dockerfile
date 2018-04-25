FROM centos:7.2.1511

RUN mkdir /home/palo/run/ -p

# copy jdk and palo binary
COPY jdk1.8.0_131/ /home/palo/run/jdk1.8.0_131/
COPY fe/ /home/palo/run/fe/

# set java home
ENV JAVA_HOME /home/palo/run/jdk1.8.0_131/

# set fe port: http/thrift/mysql/bdbje
EXPOSE 8030 9020 9030 9010

# fe log and meta-data
# VOLUME ["/home/palo/run/fe/conf" i"/home/palo/run/fe/log" "/home/palo/run/fe/palo-meta"]

WORKDIR /home/palo/run/fe/

CMD "bin/start_fe.sh"