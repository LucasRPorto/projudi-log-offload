-- =============================================================================
-- 05 — Coloca o banco em ARCHIVELOG (pré-requisito do LogMiner/Debezium)
--
-- ATENÇÃO: este script REINICIA o banco. Só deve ser chamado quando
-- v$database.log_mode não for ARCHIVELOG — a decisão fica no shell, porque
-- SHUTDOWN e STARTUP são comandos do SQL*Plus e não podem ser condicionados
-- dentro de um bloco PL/SQL.
--
-- Precisa de conexão LOCAL como sysdba (`sqlplus / as sysdba` dentro do
-- container). STARTUP não funciona através do listener.
--
-- Por que isso existe: a documentação da imagem sugere a variável de ambiente
-- ENABLE_ARCHIVELOG, mas ela NÃO é interpretada pelo entrypoint do
-- gvenzl/oracle-free — verificado no código da imagem e confirmado em execução
-- real (o banco subiu em NOARCHIVELOG com a variável definida). Ver
-- docs/decisoes.md, seção 5.
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON

SHUTDOWN IMMEDIATE;
STARTUP MOUNT;

ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;

-- FORCE LOGGING garante que nenhuma operação NOLOGGING escape do redo e crie
-- buracos invisíveis no histórico da Solução 2.
ALTER DATABASE FORCE LOGGING;

ALTER PLUGGABLE DATABASE ALL OPEN;
ALTER PLUGGABLE DATABASE ALL SAVE STATE;

SELECT log_mode, force_logging FROM v$database;

EXIT SUCCESS;
