# TCC Projudi — Handoff da Frente A (Infraestrutura)

> **Arquivo temporário, não versionado.** Serve para transportar contexto para
> outra sessão. Depois de copiar o conteúdo, apague-o ou ele acabará entrando
> num `git add -A` sem querer.
>
> Repositório: https://github.com/LucasRPorto/projudi-log-offload
> Data: 2026-07-21

---

## 1. O que é o projeto

TCC de pós-graduação (residência em TI, UFG) sobre a modernização da
arquitetura de logs de auditoria do **Projudi**, o sistema processual do TJ-GO —
aplicação Java 8 legada sobre Oracle.

**O problema.** Toda ação do usuário passa pela classe `LogPs`, que grava uma
linha na tabela `PROJUDI.LOG`, na mesma instância Oracle que atende as
transações do processo. Duas consequências:

1. A escrita de log **compete com a operação** por redo, cache e I/O. É a tabela
   que mais cresce e nunca é purgada — auditoria é obrigação legal.
2. O log **não responde à pergunta que a auditoria faz**. Ele registra *que
   houve* alteração, com valores antigo e novo concatenados em dois CLOBs
   (`VALOR_ATUAL` / `VALOR_NOVO`). Reconstruir "como estava o processo X em tal
   data" exige varrer e reinterpretar texto livre.

**Duas soluções, um ClickHouse compartilhado:**

- **Solução 1 (offload).** A `LogPs` passa a gravar direto no ClickHouse via
  JDBC. **O formato é preservado** — CLOBs viram `String`, sem parsing. Mudar
  destino e formato ao mesmo tempo tornaria impossível atribuir qualquer
  diferença de resultado a uma causa só.
- **Solução 2 (CDC).** Debezium captura mudanças da `PROJUDI.PROC` do redo log
  do Oracle → Kafka → ClickHouse materializa um histórico versionado. **Sem uma
  linha de código novo no Projudi.**

**Três frentes, sessões separadas:**

| Frente | Escopo | Estado |
|---|---|---|
| **A — Infraestrutura** | Docker Compose, DDLs, init do Oracle, scripts, docs | ✅ concluída, validada em execução real |
| **B — `log-writer`** | Cliente JDBC Java 8 que a `LogPs` vai chamar + benchmark | ⬜ esqueleto Maven pronto, `src/` vazio |
| **C — Pipeline CDC** | Ajuste do conector, validação fim a fim, latência | ⬜ infra pronta, `connector-proc.json` é template |

---

## 2. O que a Frente A entregou

42 arquivos, 17 commits.

```
projudi-log-offload/
├── .devcontainer/              ambiente para GitHub Codespaces
├── docs/
│   ├── arquitetura.md          as duas soluções + diagrama do fluxo
│   ├── ambientes.md            onde rodar e metodologia de teste
│   └── decisoes.md             17 decisões técnicas justificadas
├── infra/
│   ├── docker-compose.yml      5 serviços
│   ├── oracle/init/            orquestrador .sh + 7 SQLs
│   ├── clickhouse/ddl/         6 DDLs idempotentes
│   ├── clickhouse/config/      config.d e users.d
│   └── debezium/               Dockerfile (ojdbc) + template do conector
├── log-writer/                 esqueleto Maven da Frente B
├── scripts/                    setup, validate, register-connector, enable-archivelog
├── validacao/                  SQLs de conferência manual + evidencias/
├── Makefile                    16 alvos
└── .env.example
```

### Serviços (versões fixadas no `.env`)

| Serviço | Imagem | Porta |
|---|---|---|
| ClickHouse | `clickhouse/clickhouse-server:25.8` (LTS) | 8123 / 9000 |
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | 1521 |
| Kafka | `confluentinc/cp-kafka:8.0.6` (KRaft, sem ZooKeeper) | 29092 |
| Kafka Connect | `quay.io/debezium/connect:3.6.0.Final` + ojdbc11 | 8083 |
| Kafka UI | `kafbat/kafka-ui:v1.5.0` | 8080 |

### Oracle — schema e preparação de CDC

CDB `FREE`, PDB `FREEPDB1`, schema `PROJUDI` com as 3 tabelas de produção:
`LOG` (13 colunas), `LOG_TIPO`, `PROC` (**43 colunas**, estrutura real). Seed de
6–8 linhas em cada.

Preparação completa do LogMiner: ARCHIVELOG, FORCE LOGGING, usuário comum
`c##dbzuser` com os grants documentados pelo Debezium, supplemental logging de
banco no `CDB$ROOT` e `(ALL) COLUMNS` na `PROJUDI.PROC`.

### ClickHouse — dois bancos

**`projudi_logs.log_raw`** — espelho fiel da `PROJUDI.LOG`.
- `ORDER BY (HORA, ID_USU, ID_LOG)` cobre os três padrões dominantes: recorte
  por período, atividade de um usuário no período, e desempate único.
- `PARTITION BY toYYYYMM(HORA)`.
- O quarto padrão ("histórico da linha N da tabela T") não cabe na chave sem
  prejudicar os outros → atendido por skip index (`set` em `TABELA`,
  `bloom_filter` em `ID_TABELA`).
- `CODEC(ZSTD(3))` nos CLOBs; `Delta + ZSTD` em IDs e timestamps. Sem TTL.

**`projudi_historico`** — pipeline CDC:
`proc_cdc_kafka` (engine Kafka, `JSONAsString`) → `proc_cdc_mv`
(MATERIALIZED VIEW que desmembra o envelope Debezium) → `proc_cdc`
(MergeTree, 43 colunas + 4 de metadata CDC = **47**).

`proc_cdc` é **append-only de propósito**: uma linha por evento, nunca um
"estado atual". O estado atual é uma consulta
(`LIMIT 1 BY ID_PROC ORDER BY cdc_ts_ms DESC`), não um dado armazenado — é
justamente o histórico que a solução existe para preservar.

### Ferramental

`make setup` · `up` · `up-lite` · `validate` · `validate-lite` · `archivelog` ·
`reset` · `ch` · `sql` · `connector` · `logs` · `status`

`scripts/validate.sh` é o critério objetivo de "o ambiente está são": 30
checagens com saída ✅/❌ e exit code.

---

## 3. Empecilhos e como foram superados

Esta é a parte mais útil para o relatório — vários destes viraram decisão
técnica registrada.

### 3.1 Docker não instalável na máquina de trabalho

**Problema.** Notebook do TJ-GO, conta de domínio sem privilégio
administrativo. WSL2, VirtualMachinePlatform, HypervisorPlatform e Hyper-V todos
**desabilitados** na imagem do sistema. Isso derruba Docker Desktop, Podman
Desktop e Rancher Desktop — todos exigem admin para habilitar a virtualização.
E o ClickHouse não tem build nativo para Windows.

**Solução.** `.devcontainer/devcontainer.json` com `docker-in-docker`, que roda
o ambiente inteiro no **GitHub Codespaces**, pelo navegador. O
`docker-compose.yml` não muda; o devcontainer só o embrulha.

**Efeito colateral positivo:** com o repositório público, qualquer avaliador
reproduz o ambiente em um clique, com a própria cota. O trabalho deixa de
depender da *descrição* de um ambiente e passa a ser executável por terceiros.

**Detalhe que quase custou caro:** `hostRequirements` pedia 64 GB de disco, o
que empurraria a seleção para a máquina de 8 núcleos e consumiria a cota do
Codespaces **duas vezes mais rápido** (15 h/mês em vez de 30) sem ganho nenhum.
Corrigido para 32 GB.

### 3.2 Máquina pessoal com pouca RAM

**Problema.** 8 GB no host, apenas 3 GB disponíveis ao Docker. A pilha completa
pede 8–10 GB.

**Solução.** `make up-lite` (ClickHouse + Kafka, ~3 GB) e
`make validate-lite`. Não é validação "de segunda": o `up-lite` aplica os **seis
DDLs** no primeiro start, incluindo a MATERIALIZED VIEW, que era o item de maior
risco.

**Surpresa:** a pilha completa acabou subindo mesmo com 3 GB. O aviso do
`setup.sh` continua correto como recomendação, mas o piso real é mais baixo que
o documentado.

### 3.3 Push bloqueado pela rede corporativa

**Problema.** `git push` falhava com `CRYPT_E_REVOCATION_OFFLINE` — o servidor
de revogação de certificados é inacessível através do proxy com inspeção TLS.

**Solução.** `git config --local http.sslBackend openssl`, escopo apenas deste
repositório. Preferido a desligar a verificação de revogação
(`http.schannelCheckRevoke false`), que enfraqueceria a checagem sem
necessidade.

### 3.4 O driver JDBC da Oracle não vem na imagem do Debezium

**Problema.** O conector Oracle precisa do `ojdbc` no classpath e a imagem
oficial não o distribui. Sem ele, o conector nem registra
(`ClassNotFoundException: oracle.jdbc.OracleDriver`).

**Solução.** `Dockerfile` derivado que baixa o jar do Maven Central para
`/kafka/libs/` — o classpath do sistema, não o diretório do plugin, porque o
classloader isolado do Connect delega drivers JDBC ao classloader pai.

Descartado: bind mount de um jar baixado à mão (se o arquivo não existe, o
Docker cria um **diretório** com aquele nome e o erro não tem relação óbvia com
a causa).

**Licença:** o `ojdbc11` é publicado pela própria Oracle no Maven Central sob a
*Oracle Free Use Terms and Conditions*, que permite download sem custo nem
conta. Mesmo motivo pelo qual a imagem de banco é a `gvenzl/oracle-free` e não a
oficial da Oracle (essa exige login).

### 3.5 `ENABLE_ARCHIVELOG` não existe — descoberto só em execução

**Problema.** O Debezium usa LogMiner, que **exige** ARCHIVELOG. A decisão
original mandava usar as variáveis `ENABLE_ARCHIVELOG=true` e
`ENABLE_FORCE_LOGGING=true` no compose. O `make validate` acusou
`log_mode = NOARCHIVELOG` com as variáveis definidas. A inspeção do entrypoint
da imagem confirmou: **essas variáveis nunca são lidas**.

**Solução.** Sequência explícita em `05_enable_archivelog.sql`:
`SHUTDOWN IMMEDIATE` → `STARTUP MOUNT` → `ALTER DATABASE ARCHIVELOG` → `OPEN` →
`FORCE LOGGING` → `SAVE STATE`.

Três detalhes não óbvios:
- exige **conexão local** (`sqlplus / as sysdba` dentro do container);
  `STARTUP` não funciona pelo listener;
- a **decisão de executar fica no shell**, porque `SHUTDOWN`/`STARTUP` são
  comandos do SQL*Plus e não podem ser condicionados dentro de PL/SQL;
- depois do restart o **listener leva segundos para registrar o PDB de novo** —
  sem esperar, o script seguinte falha com ORA-12514 e o sintoma não aponta para
  a causa.

Aplicado em dois lugares: no init (ambientes novos) e em
`make archivelog` (ambiente já de pé, sem `make reset`).

A decisão 5 do `decisoes.md` foi **reescrita mantendo o registro do erro**, em
vez de apagado.

### 3.6 Dois defeitos no próprio script de validação

Ambos invisíveis à verificação estática, ambos só apareceram em execução:

**Falso negativo por corrida.** O relatório imprimiu *"banco `projudi_logs` NÃO
existe"* duas linhas acima de *"`projudi_logs.log_raw` existe"* — contradição
lógica. Causa: o status `healthy` do Docker garante que o servidor aceita
conexão, não que terminou de carregar os metadados. Consultas nessa janela
voltavam vazias. **Solução:** reexecução até 5 vezes antes de declarar falha.

**Travamento por prompt invisível.** As chamadas ao `clickhouse-client` eram
silenciadas com `>/dev/null 2>&1`. Quando o cliente pediu entrada, o prompt foi
redirecionado junto e o script ficou parado **indefinidamente** — 15 minutos até
ser interrompido à mão. **Solução:** `</dev/null` (um pedido de entrada falha na
hora, com erro legível) e `timeout` em toda chamada.

### 3.7 Escolhas de imagem que evitaram armadilhas

- **`cp-kafka` em vez de `apache/kafka`:** a segunda não declara
  `/var/lib/kafka/data` no Dockerfile, então um volume nomeado ali é criado como
  `root` enquanto o broker roda como uid 1000 — e o start falha.
- **`kafbat/kafka-ui` em vez de `provectuslabs/kafka-ui`:** o segundo foi
  descontinuado (última release de 2023) e não acompanha brokers Kafka 4.x.
- **`JSONAsString` em vez de `JSONEachRow`** no Kafka engine: com `JSONEachRow`
  seria preciso declarar `before`/`after` como `Tuple` de 43 campos, e qualquer
  mensagem fora do tipo faria o ClickHouse descartar o **bloco inteiro**
  avançando o offset — perda silenciosa de eventos, o que a Solução 2 não pode
  ter.

---

## 4. A armadilha metodológica do benchmark

**Isto é o mais importante para a Frente B.**

O Projudi, quando roda localmente no Eclipse, aponta para a base Oracle de
**desenvolvimento/homologação do TJ-GO** — nunca para uma base local. Isso cria
uma armadilha na medição:

```
❌ Projudi (notebook TJ) ── LAN corporativa ──► Oracle dev (TJ-GO)
                        └─ internet + túnel ──► ClickHouse (remoto)
```

Comparar um destino na LAN com outro do outro lado da internet **não mede nada**
sobre Oracle × ClickHouse: mede latência de rede. Seria descartado em arguição.

**Duas consequências:**

1. O container Oracle deste repositório **não é redundante** com a base de
   desenvolvimento. Ele é o **grupo de controle** do experimento.
2. O benchmark **não deve depender de rodar o Projudi**. O `log-writer` é
   biblioteca; a medição é um harness em `log-writer/src/test/` que escreve N
   registros nos dois destinos e cronometra os dois, no mesmo host.

A integração com o Projudi real é validação **funcional**, separada da medição.

**Regra de ouro do trabalho em dupla:** os números finais do benchmark têm que
sair de **um único ambiente de referência** (a máquina de 32 GB do colega).
Ambientes diferentes produzem medições que não se combinam.

---

## 5. Estado atual da validação

`make validate` completo, executado em máquina real:

```
30 passaram   0 falharam   1 aviso
```

Confirmado em execução: os 6 DDLs do ClickHouse (incluindo a MATERIALIZED VIEW
com `JSONExtract`/`accurateCastOrNull`/`toDateTime64` sobre `Nullable`), as 47
colunas da `proc_cdc`, o usuário de aplicação, o ciclo de escrita e leitura em
`log_raw`, o schema `PROJUDI` com as 43 colunas da `PROC` e o seed, o Kafka, o
Kafka Connect com o plugin Oracle do Debezium e o `ojdbc11` no classpath.

**O único aviso era `NOARCHIVELOG`** (item 3.5 acima). A correção está escrita
mas **ainda não foi executada** — 2 commits não publicados no momento em que
este documento foi gerado.

### ⚠️ Portão antes de avançar

```bash
git push
make archivelog
make validate      # esperado: 0 falharam, 0 avisos
```

**Enquanto isso não passar, a Frente A não está fechada.** A correção do
ARCHIVELOG é código não validado — e foi exatamente esse tipo de suposição que
falhou três vezes nesta sessão (itens 3.5 e 3.6).

---

## 6. Próximos passos

### Imediato
Rodar o portão da seção 5. Se der `0 falharam, 0 avisos`, a Frente A está
fechada.

### Frente C — colega (máquina de 32 GB, ambiente de referência)
1. `make validate` num ambiente **novo** (`make reset && make up`), para
   confirmar que ambiente do zero já nasce em ARCHIVELOG.
2. `./scripts/register-connector.sh`
3. Gerar movimento com a seção 5 de `validacao/02_oracle_origem.sql`
4. Conferir chegada com `validacao/03_consultas_cdc.sql`
5. Medir latência do pipeline e completude dos eventos

### Frente B — `log-writer`
1. **Ler a `LogPs` real** em `../projudi` — mapear exatamente o que ela monta em
   `VALOR_ATUAL`/`VALOR_NOVO` e onde faz o INSERT hoje. Sem isso, qualquer
   decisão de API é chute.
2. Definir a fronteira: substituto direto do INSERT, sem a `LogPs` saber que
   mudou de destino.
3. **Decidir batching e comportamento em falha** — grava síncrono ou em fila? Se
   o ClickHouse cair, o log se perde ou bloqueia o processo? É a decisão de
   projeto mais pesada da frente; registrar em `decisoes.md`.
4. Escrever o harness de benchmark em `log-writer/src/test/`, com parâmetros
   explícitos e saída colável no relatório, sem depender de estado local.

**Restrição técnica:** Java 8 (é o que o `pom.xml` do Projudi declara), o que
fixa `clickhouse-jdbc` 0.7.2 — última linha compatível. Classificador `all`
(uber jar sombreado) para evitar conflito no classpath antigo do Projudi.

**Conexão:** `jdbc:ch://localhost:8123/projudi_logs`, usuário `projudi_app`.

---

## 7. Para a seção de metodologia do TCC

Três suposições caíram só em execução real, nenhuma detectada por verificação
estática:

1. `ENABLE_ARCHIVELOG` seria interpretada pela imagem — **não é**;
2. `healthy` do Docker significaria "pronto para consulta" — **não significa**;
3. silenciar a saída de um comando seria inócuo — **esconde prompts e trava**.

É argumento empírico direto de que verificação estática não substitui execução,
e vale ser dito no relatório com os números: a Frente A passou por verificação
estática completa (sintaxe, YAML, XML, consistência de 47 colunas entre três
artefatos, existência de tags de imagem) e ainda assim carregava três defeitos
que só a primeira execução revelou.
