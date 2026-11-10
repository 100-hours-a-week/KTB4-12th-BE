FROM rabbitmq:4.4.0-management

RUN rabbitmq-plugins enable --offline rabbitmq_prometheus

COPY rabbitmq.conf /etc/rabbitmq/conf.d/20-definitions.conf
COPY *-definitions.json /etc/rabbitmq/definitions/
