FROM rabbitmq:4.4.0-management

RUN rabbitmq-plugins enable --offline rabbitmq_prometheus

COPY rabbitmq.conf /etc/rabbitmq/conf.d/20-definitions.conf
COPY report-definitions.json /etc/rabbitmq/definitions/00-report.json
COPY profiling-definitions.json /etc/rabbitmq/definitions/20-profiling.json
COPY payment-definitions.json /etc/rabbitmq/definitions/30-payment.json
