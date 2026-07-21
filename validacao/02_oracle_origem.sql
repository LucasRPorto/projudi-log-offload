-- =============================================================================
-- Validação — schema de origem no Oracle
--
-- Como rodar:
--   make sql   e cole os blocos
-- ou:
--   docker compose --env-file .env -f infra/docker-compose.yml exec -T oracle \
--     sqlplus -S PROJUDI/projudi_dev@//localhost:1521/FREEPDB1 \
--     < validacao/02_oracle_origem.sql
--
-- Serve para dois fins: conferir que o seed subiu, e gerar movimento na
-- PROJUDI.PROC para exercitar o CDC da Frente C.
-- =============================================================================

SET LINESIZE 200
SET PAGESIZE 100

-- ---------------------------------------------------------------------------
-- 1. As três tabelas existem e têm dados?
-- ---------------------------------------------------------------------------
SELECT 'LOG_TIPO' AS tabela, COUNT(*) AS linhas FROM PROJUDI.LOG_TIPO
UNION ALL
SELECT 'LOG',      COUNT(*) FROM PROJUDI.LOG
UNION ALL
SELECT 'PROC',     COUNT(*) FROM PROJUDI.PROC;


-- ---------------------------------------------------------------------------
-- 2. A PROC tem mesmo as 43 colunas de produção?
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS colunas_proc
FROM   user_tab_columns
WHERE  table_name = 'PROC';


-- ---------------------------------------------------------------------------
-- 3. Amostra dos logs, com o tipo resolvido.
-- ---------------------------------------------------------------------------
COLUMN log_tipo    FORMAT A32
COLUMN tabela      FORMAT A12
COLUMN valor_novo  FORMAT A60

SELECT l.ID_LOG,
       TO_CHAR(l.HORA, 'YYYY-MM-DD HH24:MI:SS') AS hora,
       l.ID_USU,
       t.LOG_TIPO                                AS log_tipo,
       l.TABELA                                  AS tabela,
       SUBSTR(l.VALOR_NOVO, 1, 60)               AS valor_novo
FROM   PROJUDI.LOG l
       LEFT JOIN PROJUDI.LOG_TIPO t ON t.ID_LOG_TIPO = l.ID_LOG_TIPO
ORDER  BY l.HORA;


-- ---------------------------------------------------------------------------
-- 4. Pré-requisitos do CDC (rodar como SYS: `make sql` conecta como PROJUDI,
--    use o comando do cabeçalho trocando o usuário por sys ... as sysdba).
-- ---------------------------------------------------------------------------
-- SELECT log_mode, supplemental_log_data_min, supplemental_log_data_all
-- FROM   v$database;

-- Supplemental log group da PROC (deve existir ao menos 1):
SELECT log_group_name, always
FROM   user_log_groups
WHERE  table_name = 'PROC';


-- ===========================================================================
-- 5. Geração de movimento para o CDC.
--
--    Descomente e execute DEPOIS de registrar o conector Debezium
--    (./scripts/register-connector.sh). Cada comando abaixo deve produzir
--    um evento no tópico projudi.PROJUDI.PROC e, em seguida, uma linha em
--    projudi_historico.proc_cdc.
-- ===========================================================================

-- -- (c) INSERT -> op = 'c'
-- INSERT INTO PROJUDI.PROC (
--     ID_PROC, PROC_NUMERO, DIGITO_VERIFICADOR, FORUM_CODIGO, ANO,
--     ID_PROC_TIPO, ID_PROC_FASE, ID_PROC_STATUS, ID_SERV, ID_AREA,
--     SEGREDO_JUSTICA, APENSO, VALOR, DATA_RECEBIMENTO, LOCALIZADOR,
--     JULGADO_2_GRAU, DIGITAL100
-- ) VALUES (
--     PROJUDI.SEQ_PROC.NEXTVAL, 202699001, 33, 2609, 2026,
--     1, 1, 1, 41, 1,
--     0, 0, 4200.00, SYSDATE, 'TESTE-CDC',
--     0, 1
-- );
-- COMMIT;

-- -- (u) UPDATE -> op = 'u'  (com supplemental logging ALL, o `after` vem completo)
-- UPDATE PROJUDI.PROC
--    SET ID_PROC_FASE   = 2,
--        ID_PROC_STATUS = 3,
--        VALOR          = 9999.99,
--        LOCALIZADOR    = 'TESTE-CDC-ALTERADO'
--  WHERE ID_PROC = 1000;
-- COMMIT;

-- -- (d) DELETE -> op = 'd'  (a MV materializa a imagem `before`)
-- DELETE FROM PROJUDI.PROC WHERE ID_PROC = 1005;
-- COMMIT;
