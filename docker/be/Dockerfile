FROM centos:7.2.1511

RUN mkdir /home/palo/run/ -p

# copy be
COPY be/ /home/palo/run/be/

# set be port: thrift/rpc/http/heartbeat
EXPOSE 9060 9070 8040 9050

# fe log and meta-data
VOLUME ["/home/palo/run/be/conf", "/home/palo/run/be/log", "/home/palo/run/be/data/"]

WORKDIR /home/palo/run/be/

CMD "bin/start_be.sh"