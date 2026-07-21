-- =============================================================================
-- 40 — PDB FREEPDB1: tabelas do schema PROJUDI
--
-- Réplica da estrutura de produção para as três tabelas que interessam ao MVP:
--   LOG       — origem da Solução 1 (o LogPs grava aqui hoje; passará a gravar
--               no ClickHouse). Mantida no ambiente local para permitir a
--               comparação lado a lado Oracle x ClickHouse.
--   LOG_TIPO  — dimensão dos tipos de log.
--   PROC      — alvo da Solução 2 (CDC). 43 colunas, estrutura de produção.
--
-- Sem SET DEFINE: nenhum parâmetro é usado aqui, e desligar a substituição
-- evita que um eventual '&' em valor default seja interpretado pelo SQL*Plus.
-- =============================================================================

WHENEVER SQLERROR EXIT SQL.SQLCODE
WHENEVER OSERROR  EXIT FAILURE
SET ECHO ON
SET DEFINE OFF

-- -----------------------------------------------------------------------------
-- PROJUDI.LOG_TIPO
-- -----------------------------------------------------------------------------
CREATE TABLE PROJUDI.LOG_TIPO (
  ID_LOG_TIPO     NUMBER(10) PRIMARY KEY,
  LOG_TIPO_CODIGO NUMBER(10) NOT NULL,
  LOG_TIPO        VARCHAR2(240) NOT NULL,
  CODIGO_TEMP     NUMBER(10),
  STATUS          NUMBER(1) NOT NULL
) TABLESPACE projudi_tbs;

-- -----------------------------------------------------------------------------
-- PROJUDI.LOG
-- -----------------------------------------------------------------------------
CREATE TABLE PROJUDI.LOG (
  ID_LOG          NUMBER(24) PRIMARY KEY,
  ID_LOG_TIPO     NUMBER(10) NOT NULL,
  ID_USU          NUMBER(24) NOT NULL,
  IP_COMPUTADOR   VARCHAR2(180),
  DATA            DATE,
  HORA            DATE,
  TABELA          VARCHAR2(240),
  VALOR_ATUAL     CLOB,
  VALOR_NOVO      CLOB,
  CODIGO_TEMP     NUMBER(10),
  ID_TABELA       NUMBER(24),
  HASH            CHAR(32),
  QTD_ERROS_DIA   NUMBER(6)
) TABLESPACE projudi_tbs;

CREATE INDEX PROJUDI.IDX_LOG_HORA     ON PROJUDI.LOG (HORA)               TABLESPACE projudi_tbs;
CREATE INDEX PROJUDI.IDX_LOG_USU_HORA ON PROJUDI.LOG (ID_USU, HORA)       TABLESPACE projudi_tbs;
CREATE INDEX PROJUDI.IDX_LOG_TABELA   ON PROJUDI.LOG (TABELA, ID_TABELA)  TABLESPACE projudi_tbs;

-- -----------------------------------------------------------------------------
-- PROJUDI.PROC — 43 colunas
-- -----------------------------------------------------------------------------
CREATE TABLE PROJUDI.PROC (
  ID_PROC                        NUMBER(24) PRIMARY KEY,
  ID_PROC_DEPENDENTE             NUMBER(24),
  PROC_NUMERO                    NUMBER(24) NOT NULL,
  DIGITO_VERIFICADOR             NUMBER(5),
  FORUM_CODIGO                   NUMBER(12) NOT NULL,
  ANO                            NUMBER(5),
  ID_PROC_TIPO                   NUMBER(10) NOT NULL,
  ID_PROC_PRIOR                  NUMBER(10),
  ID_PROC_FASE                   NUMBER(10) NOT NULL,
  ID_PROC_STATUS                 NUMBER(10) NOT NULL,
  ID_SERV                        NUMBER(10) NOT NULL,
  ID_SERV_ORIGEM                 NUMBER(10),
  ID_AREA                        NUMBER(5) NOT NULL,
  ID_OBJETO_PEDIDO               NUMBER(10),
  ID_CLASSIFICADOR               NUMBER(24),
  ID_PROC_SIT                    NUMBER(10),
  TCO_NUMERO                     VARCHAR2(120),
  SEGREDO_JUSTICA                NUMBER(3),
  APENSO                         NUMBER(3),
  VALOR                          NUMBER(20,2),
  DATA_RECEBIMENTO               DATE NOT NULL,
  DATA_ARQUIVAMENTO              DATE,
  PROC_DIRETORIO                 VARCHAR2(40),
  CODIGO_TEMP                    NUMBER(10),
  PROC_NUMERO_ANTIGO_TEMP        NUMBER(24),
  CODRE_CURSO_TEMP               NUMBER(24),
  TABELA_ORIGEM_TEMP             VARCHAR2(400),
  EFEITO_SUSPENSIVO              NUMBER(3),
  PENHORA                        NUMBER(3),
  DATA_TRANSITO_JULGADO          DATE,
  JULGADO_2_GRAU                 NUMBER(1),
  VALOR_CONDENACAO               NUMBER(20,2),
  ID_CUSTA_TIPO                  NUMBER(4),
  ID_AREA_DIST                   NUMBER(10),
  DATA_DIGITALIZACAO             DATE,
  PROCESSO_FISICO_TIPO           VARCHAR2(4),
  PROCESSO_FISICO_NUMERO         VARCHAR2(20),
  PROCESSO_FISICO_COMARCA_CODIGO NUMBER(10),
  LOCALIZADOR                    VARCHAR2(40),
  DATA_PRESCRICAO                DATE,
  DIGITAL100                     NUMBER(1),
  ID_PROC_PRIOR_1                NUMBER(10),
  ID_CLASSIFICADOR2              NUMBER(24)
) TABLESPACE projudi_tbs;

-- -----------------------------------------------------------------------------
-- Sequences.
--
-- Não existem no schema de produção (lá os IDs vêm da aplicação), mas aqui elas
-- deixam as frentes B e C gerarem carga de teste sem precisar administrar IDs à
-- mão: `INSERT INTO PROJUDI.PROC (ID_PROC, ...) VALUES (PROJUDI.SEQ_PROC.NEXTVAL, ...)`.
-- Ver docs/decisoes.md.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE PROJUDI.SEQ_LOG  START WITH 1000 INCREMENT BY 1 NOCACHE;
CREATE SEQUENCE PROJUDI.SEQ_PROC START WITH 1000 INCREMENT BY 1 NOCACHE;

EXIT SUCCESS;
