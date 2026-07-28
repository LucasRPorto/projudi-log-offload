# Arquitetura

## O problema

O Projudi é o sistema processual do TJ-GO: Java legado sobre Oracle. Toda ação
relevante do usuário passa pela classe `LogPs`, que grava uma linha na tabela
`PROJUDI.LOG` — na mesma instância Oracle que atende as transações do processo.

Duas consequências disso motivam este trabalho:

1. **A escrita de log compete com a operação.** Cada `INSERT` na `PROJUDI.LOG`
   consome redo, buffer cache e I/O do mesmo banco que precisa responder ao
   usuário. A tabela é a que mais cresce no sistema e nunca é purgada, porque a
   auditoria é obrigação legal.

2. **O log não responde à pergunta que a auditoria realmente faz.** Ele registra
   *que houve* uma alteração, com os valores antigo e novo concatenados em dois
   CLOBs (`VALOR_ATUAL` / `VALOR_NOVO`). Reconstruir "como estava o processo
   número X em tal data" exige varrer e reinterpretar texto livre.

O MVP ataca as duas frentes com duas soluções independentes, que compartilham um
único ClickHouse single-node.

---

## Visão geral

```
                        ORACLE (PROJUDI)
        ┌──────────────────────────────────────────────┐
        │                                              │
        │   PROJUDI.LOG          PROJUDI.PROC          │
        │   (auditoria)          (processos)           │
        │        ▲                     │               │
        │        │                     │ redo log      │
        └────────┼─────────────────────┼───────────────┘
                 │                     │
      Solução 1  │                     │  Solução 2
      (offload)  │                     │  (CDC)
                 │                     ▼
                 │            ┌──────────────────┐
                 │            │  Kafka Connect   │
                 │            │  + Debezium      │
                 │            │  Oracle/LogMiner │
                 │            └────────┬─────────┘
                 │                     │
                 │                     ▼
                 │            ┌──────────────────┐
                 │            │      KAFKA       │
                 │            │  (KRaft, 1 nó)   │
                 │            │                  │
                 │            │ projudi.PROJUDI  │
                 │            │      .PROC       │
                 │            └────────┬─────────┘
                 │                     │
   ┌─────────────┴──────┐              │
   │  Projudi (Java 8)  │              │
   │  classe LogPs      │              │
   │  → JDBC direto     │              │
   └─────────────┬──────┘              │
                 │                     │
                 ▼                     ▼
        ╔════════════════════════════════════════════╗
        ║                CLICKHOUSE                  ║
        ║                                            ║
        ║  projudi_logs          projudi_historico   ║
        ║  ┌──────────────┐      ┌────────────────┐  ║
        ║  │ log_raw      │      │ proc_cdc_kafka │  ║
        ║  │ (MergeTree)  │      │ (Kafka engine) │  ║
        ║  │              │      └───────┬────────┘  ║
        ║  │ log_tipo     │              │ MV        ║
        ║  └──────────────┘              ▼           ║
        ║                        ┌────────────────┐  ║
        ║                        │ proc_cdc       │  ║
        ║                        │ (MergeTree,    │  ║
        ║                        │  append-only)  │  ║
        ║                        └────────────────┘  ║
        ╚════════════════════════════════════════════╝
```

---

## Solução 1 — Offload de log

**O que muda:** a classe `LogPs` passa a gravar em `projudi_logs.log_raw` no
ClickHouse, via JDBC, em vez de `PROJUDI.LOG` no Oracle.

**O que NÃO muda:** o formato. Os CLOBs `VALOR_ATUAL` e `VALOR_NOVO` viram
`String` sem nenhum parsing, e as 13 colunas são preservadas uma a uma. Isso é
deliberado: mudar destino e formato ao mesmo tempo tornaria impossível atribuir
qualquer diferença de resultado a uma causa só. O parsing, se valer a pena, é
trabalho de uma etapa posterior.

**Onde está:**

| Peça | Caminho |
|---|---|
| Tabela destino | `infra/clickhouse/ddl/02_log_raw.sql` |
| Cliente Java (Frente B) | `log-writer/` — implementado; ver `log-writer/README.md` |
| Consultas de validação | `validacao/01_clickhouse_logs.sql` |

**Decisões de modelagem** (detalhadas em comentário no próprio DDL):

- `ORDER BY (HORA, ID_USU, ID_LOG)` — cobre os três padrões dominantes: recorte
  por período, atividade de um usuário no período, e desempate único.
- `PARTITION BY toYYYYMM(HORA)` — poda de partição alinhada ao recorte mensal.
- Data skipping index em `TABELA` e `ID_TABELA` — atende o quarto padrão
  ("histórico da linha N da tabela T") sem sacrificar a chave de ordenação.
- `CODEC(ZSTD(3))` nos CLOBs, `Delta + ZSTD` em IDs e timestamps.
- Sem TTL: retenção indefinida, mesma política do Oracle.

---

## Solução 2 — Histórico por CDC

**O que muda:** as alterações da `PROJUDI.PROC` passam a ser capturadas do redo
log do Oracle pelo Debezium (via LogMiner), publicadas no Kafka e materializadas
no ClickHouse como um histórico versionado — sem que a aplicação Java saiba que
isso existe, e sem uma linha de código no Projudi.

**O ponto central:** `projudi_historico.proc_cdc` é **append-only**. Uma linha
por evento, nunca um "estado atual". O estado atual é uma consulta
(`LIMIT 1 BY ID_PROC ORDER BY cdc_ts_ms DESC`), não um dado armazenado — é
justamente o histórico que a solução existe para preservar.

**Fluxo:**

```
UPDATE PROJUDI.PROC ...
        │
        ▼
  redo log do Oracle          ← exige ARCHIVELOG + supplemental logging (ALL)
        │
        ▼
  Debezium Oracle Connector   ← LogMiner; infra/debezium/connector-proc.json
        │
        ▼
  tópico projudi.PROJUDI.PROC ← envelope { op, ts_ms, before, after, source }
        │
        ▼
  proc_cdc_kafka              ← Kafka engine, formato JSONAsString
        │
        ▼
  proc_cdc_mv                 ← MATERIALIZED VIEW: JSONExtract campo a campo
        │                        op=c/u/r → usa `after`;  op=d → usa `before`
        ▼
  proc_cdc                    ← MergeTree, 43 colunas + 4 de metadata CDC
```

**Onde está:**

| Peça | Caminho |
|---|---|
| Preparação do Oracle (ARCHIVELOG, `c##dbzuser`, supplemental logging) | `infra/oracle/init/` |
| Configuração do conector | `infra/debezium/connector-proc.json` |
| Registro do conector | `scripts/register-connector.sh` |
| Tabela Kafka / MV / destino | `infra/clickhouse/ddl/04`, `05`, `03` |
| Consultas de validação | `validacao/03_consultas_cdc.sql` |

**Metadata CDC** anexada a cada evento:

| Coluna | Origem | Para quê |
|---|---|---|
| `cdc_op` | `op` do envelope | `c` insert, `u` update, `d` delete, `r` snapshot |
| `cdc_ts_ms` | `ts_ms` do envelope | ordena o histórico de um mesmo `ID_PROC` |
| `cdc_scn` | `source.scn` | rastreabilidade até o redo log do Oracle |
| `ingestion_ts` | `now()` no ClickHouse | chave de particionamento; mede a latência do pipeline |

---

## Por que as duas compartilham um ClickHouse

Não é economia de container: é a premissa do trabalho. As duas soluções atacam o
mesmo gargalo (tudo mora no Oracle transacional) e a tese é que um único destino
analítico colunar atende os dois casos de uso com modelagens diferentes. Bancos
separados (`projudi_logs`, `projudi_historico`) mantêm o isolamento lógico sem
duplicar infraestrutura.

---

## Fronteiras entre as frentes

| Frente | Escopo | Estado |
|---|---|---|
| **A — Infraestrutura** | Docker Compose, DDLs, preparação do Oracle, scripts, validação | esta sessão |
| **B — `log-writer`** | Cliente JDBC Java 8 que a `LogPs` vai chamar; batching, tratamento de falha, benchmark contra o Oracle | implementado, 57 testes unitários verdes; conexão real já validada, gravação e benchmark aguardam ambiente |
| **C — Pipeline CDC** | Ajuste fino do conector, validação fim a fim, medição de latência e de completude | infra pronta, `connector-proc.json` é template |

A Frente A entrega o ambiente de pé e validado. B e C começam sem tocar em
infraestrutura.
