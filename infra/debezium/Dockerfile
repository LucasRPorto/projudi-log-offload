# =============================================================================
# Kafka Connect (Debezium) + driver JDBC da Oracle
#
# O Debezium Oracle Connector usa LogMiner através de uma conexão JDBC comum e
# NÃO redistribui o driver da Oracle dentro da imagem oficial. Sem o ojdbc no
# classpath o conector falha no registro com
#   java.lang.ClassNotFoundException: oracle.jdbc.OracleDriver
#
# O driver é baixado do Maven Central no build. Ele é publicado pela própria
# Oracle sob a licença "Oracle Free Use Terms and Conditions" (FUTC), que
# permite download e uso sem custo — por isso o build automático é aceitável
# aqui, diferente das imagens de banco da Oracle (que exigem conta).
#
# O jar vai para /kafka/libs (classpath do sistema) e não para o diretório do
# plugin: o classloader isolado de plugin do Connect delega classes de driver
# JDBC ao classloader pai, então esta é a colocação que funciona tanto para o
# conector Oracle quanto para qualquer outro que venha a precisar do driver.
# =============================================================================

ARG DEBEZIUM_VERSION=3.6.0.Final
FROM quay.io/debezium/connect:${DEBEZIUM_VERSION}

ARG OJDBC_VERSION=23.8.0.25.04
ARG OJDBC_URL=https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/${OJDBC_VERSION}/ojdbc11-${OJDBC_VERSION}.jar

USER root
ADD ${OJDBC_URL} /kafka/libs/ojdbc11.jar
RUN chmod 0644 /kafka/libs/ojdbc11.jar
USER kafka
