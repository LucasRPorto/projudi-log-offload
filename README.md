# projudi-log-offload

Infraestrutura do TCC da pós-graduação (residência em TI, UFG) sobre a
modernização da arquitetura de logs de auditoria do **Projudi**, o sistema
processual do TJ-GO — uma aplicação Java legada sobre Oracle. Hoje toda ação do
usuário passa pela classe `LogPs`, que grava uma linha na tabela `PROJUDI.LOG`,
na mesma instância Oracle que atende as transações do processo. É a tabela que
mais cresce no sistema, nunca é purgada (a auditoria é obrigação legal) e sua
escrita disputa redo, cache e I/O com a operação.

Este repositório contém o ambiente e os artefatos de infraestrutura de duas
soluções que atacam esse gargalo e compartilham um único ClickHouse:
a **Solução 1 (offload)**, em que a `LogPs` passa a gravar direto no ClickHouse
via JDBC preservando o formato atual do log; e a **Solução 2 (CDC)**, em que o
Debezium captura as mudanças da `PROJUDI.PROC` do redo log do Oracle, publica no
Kafka e o ClickHouse materializa um histórico versionado — sem uma linha de
código novo no Projudi. O detalhamento está em
[`docs/arquitetura.md`](docs/arquitetura.md); as decisões técnicas e suas
justificativas, em [`docs/decisoes.md`](docs/decisoes.md).

> O código do Projudi **não** faz parte deste repositório. Ele fica clonado
> separadamente (`../projudi`) e não é modificado por nada aqui.

---

## Pré-requisitos

| Item | Mínimo | Observação |
|---|---|---|
| Docker Engine | 24+ | Docker Desktop no Windows/macOS |
| Docker Compose | **v2** | o `docker-compose` v1 legado não serve (usamos `depends_on.condition`) |
| RAM para o Docker | 12 GB | Oracle + Kafka + Connect + ClickHouse pedem ~8–10 GB juntos |
| Disco livre | ~15 GB | imagens (~3 GB) + volumes |
| CPUs | 4 | com menos, o primeiro start do Oracle fica muito lento |
| `make` | opcional | veja [Sem o `make`](#sem-o-make) |

**Portas que precisam estar livres:** `1521` (Oracle), `8080` (Kafka UI),
`8083` (Kafka Connect), `8123` e `9000` (ClickHouse), `29092` (Kafka).
Todas são configuráveis no `.env`.

No Windows, use **Git Bash** ou **WSL** para rodar os scripts `.sh` — eles não
funcionam no `cmd.exe` nem no PowerShell.

> **Sem Docker na sua máquina?** Máquinas corporativas costumam bloquear a
> instalação (exige privilégio administrativo para habilitar WSL2/Hyper-V). O
> repositório traz um `.devcontainer/` que roda o ambiente inteiro no
> **GitHub Codespaces**, pelo navegador — veja a seção seguinte.
> A escolha entre os ambientes e o impacto de cada um na medição de desempenho
> estão em [`docs/ambientes.md`](docs/ambientes.md).

---

## Rodando no GitHub Codespaces

Não instala nada na máquina. No GitHub, no botão **Code → Codespaces →
New with options**, escolha o tipo de máquina de **4 núcleos / 16 GB** e crie.

O tipo padrão de **2 núcleos / 8 GB não funciona**: o Oracle é morto pelo OOM
killer. O `.devcontainer/devcontainer.json` declara esse requisito, então a
interface já filtra os tipos compatíveis.

Quando o Codespace abrir:

```bash
make setup && make up && make validate
```

Dois cuidados com a cota (120 core-hours/mês no plano Free, 180 no Pro):

- o consumo é multiplicado pelo número de núcleos, então 4 núcleos consomem
  **4 core-hours por hora** — cerca de 30 h/mês no Free;
- **apague o codespace ao terminar**, não apenas pare. Armazenamento é cobrado
  enquanto ele existir. `make up` reconstrói tudo a partir dos DDLs.

Aumente o *idle timeout* padrão de 30 min em *Settings → Codespaces* para não
perder o ambiente no meio de um experimento.

---

## Subindo o ambiente do zero

```bash
git clone https://github.com/LucasRPorto/projudi-log-offload.git
cd projudi-log-offload

./scripts/setup.sh     # ou: make setup
make up
make validate
```

O que cada passo faz:

1. **`./scripts/setup.sh`** — confere Docker, Compose, RAM, CPUs e portas; cria
   o `.env` a partir do `.env.example`; baixa as imagens; e constrói a imagem do
   Kafka Connect, que baixa o driver JDBC da Oracle do Maven Central.
2. **`make up`** — sobe os cinco serviços e espera todos ficarem saudáveis.
   **O primeiro start do Oracle leva de 2 a 5 minutos** — ele cria o schema
   `PROJUDI`, as três tabelas, os dados de exemplo e toda a preparação do CDC.
   Os starts seguintes são rápidos.
3. **`make validate`** — bateria de verificação; imprime ✅/❌ por item.

Ao final, `make validate` deve terminar com `0 falharam`.

---

## Acesso aos serviços

Credenciais e portas vêm do `.env` (valores abaixo são os padrões de
desenvolvimento do `.env.example`).

### ClickHouse

| | |
|---|---|
| HTTP | http://localhost:8123 (`/play` para a interface web) |
| Nativo | `localhost:9000` |
| Usuário de aplicação | `projudi_app` / `projudi_app_dev` |
| Usuário interativo | `default`, sem senha |
| Bancos | `projudi_logs`, `projudi_historico` |

```bash
make ch                                            # cliente interativo
curl 'http://localhost:8123/?query=SELECT+version()'
```

JDBC para a Frente B:

```
jdbc:ch://localhost:8123/projudi_logs?user=projudi_app&password=projudi_app_dev
```

### Oracle

| | |
|---|---|
| Host | `localhost:1521` |
| CDB / PDB | `FREE` / `FREEPDB1` |
| Schema de origem | `PROJUDI` / `projudi_dev` |
| SYS | `sys` / `oracle_dev` as sysdba |
| Usuário do Debezium | `c##dbzuser` / `dbz_dev` |
| Tabelas | `LOG`, `LOG_TIPO`, `PROC` |

```bash
make sql                                           # sqlplus como PROJUDI
```

### Kafka

| | |
|---|---|
| Do host | `localhost:29092` |
| Entre containers | `kafka:9092` |

```bash
docker compose --env-file .env -f infra/docker-compose.yml \
  exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### Kafka Connect

| | |
|---|---|
| REST | http://localhost:8083 |

```bash
curl -s localhost:8083/connector-plugins | grep -i oracle
./scripts/register-connector.sh            # registra o conector da PROC
./scripts/register-connector.sh --status   # estado do conector
```

### Kafka UI

http://localhost:8080 — tópicos, mensagens e o estado do Connect. Opcional:
se falhar, nada mais é afetado.

---

## Comandos do dia a dia

```bash
make help              # lista todos os alvos
make up                # sobe e espera ficar saudável
make up-lite           # só ClickHouse + Kafka (~3 GB), para máquinas pequenas
make down              # para, preservando os dados
make restart           # reinicia sem apagar dados
make reset             # APAGA os volumes e recria do zero (pede confirmação)
make status            # estado e saúde dos containers
make logs s=oracle     # segue o log de um serviço
make validate          # bateria de validação
make validate-lite     # valida só o ClickHouse (para quem subiu com up-lite)
make archivelog        # coloca o Oracle em ARCHIVELOG (pré-requisito do CDC)
make ch                # clickhouse-client interativo
make sql               # sqlplus interativo
make connector         # registra o conector Debezium
make build             # reconstrói a imagem do Connect
```

### Sem o `make`

Todos os alvos são atalhos sobre a mesma linha de comando. Os equivalentes:

```bash
DC="docker compose --env-file .env -f infra/docker-compose.yml"

$DC up -d --wait --wait-timeout 600     # make up
$DC down                                # make down
$DC down -v                             # make reset (sem confirmação!)
$DC ps                                  # make status
$DC logs -f --tail=200 oracle           # make logs s=oracle
$DC exec clickhouse clickhouse-client   # make ch

./scripts/validate.sh                   # make validate
./scripts/register-connector.sh         # make connector
```

No Windows, `make` pode ser instalado com `winget install GnuWin32.Make`,
`scoop install make` ou `choco install make` — ou use os comandos acima
diretamente no Git Bash.

---

## Estrutura do repositório

```
projudi-log-offload/
├── .devcontainer/              ambiente para GitHub Codespaces / Dev Containers
├── docs/
│   ├── arquitetura.md          visão das duas soluções + diagrama
│   ├── ambientes.md            onde rodar e como testar cada frente
│   └── decisoes.md             decisões técnicas e justificativas
├── infra/
│   ├── docker-compose.yml      os cinco serviços
│   ├── oracle/init/            orquestrador + SQLs de criação do schema/CDC
│   ├── clickhouse/ddl/         DDLs idempotentes aplicados no primeiro start
│   ├── clickhouse/config/      config.d e users.d do servidor
│   └── debezium/               Dockerfile (ojdbc) + template do conector
├── log-writer/                 biblioteca da Frente B (ver log-writer/README.md)
├── scripts/
│   ├── setup.sh                pré-requisitos, .env, pull, build
│   ├── validate.sh             bateria de validação
│   └── register-connector.sh   registro do conector Debezium
├── validacao/                  SQLs de conferência e evidências
├── Makefile
└── .env.example
```

---

## Troubleshooting

### O Oracle demora muito / `make up` estoura o tempo limite

Normal no **primeiro** start: a imagem precisa abrir o banco, habilitar
ARCHIVELOG e rodar todo o init. De 2 a 5 minutos numa máquina com 4 CPUs; mais
em máquinas modestas.

```bash
make logs s=oracle
```

Procure por `DATABASE IS READY TO USE!` e pelas linhas `[init-projudi]`. Se o
init falhou, a mensagem de erro está aí. Depois de corrigir a causa, é preciso
`make reset` — os scripts de `initdb.d` só rodam quando o volume está vazio.

### `bind: address already in use`

Alguma porta já está ocupada. Descubra qual no erro do compose e mude a
correspondente no `.env` (`CLICKHOUSE_HTTP_PORT`, `ORACLE_PORT`, …). No Windows,
a `1521` costuma ser ocupada por um Oracle instalado localmente e a `8080` por
qualquer coisa.

### Pouca RAM

Sintoma típico: o Oracle sobe e morre, ou o ClickHouse é morto pelo OOM killer.
No Docker Desktop, aumente em *Settings → Resources → Memory* (mínimo 12 GB).

Se a máquina não chega lá, use o modo reduzido — ClickHouse e Kafka apenas,
cerca de 3 GB:

```bash
make up-lite
make validate-lite
```

Isso não é uma validação "de segunda": `up-lite` aplica os **seis DDLs** do
ClickHouse no primeiro start, incluindo a `MATERIALIZED VIEW proc_cdc_mv` com as
expressões `JSONExtract`, e cria o usuário de aplicação. Se o ClickHouse subir
saudável e o `validate-lite` passar, a parte de maior risco do ambiente está
confirmada — falta só o que depende do Oracle e do Kafka Connect.

O modo reduzido serve para iterar o `log-writer` (Frente B). **Não serve para o
benchmark:** sem o Oracle local não existe grupo de controle — ver
[`docs/ambientes.md`](docs/ambientes.md), seção 3.

### `ClassNotFoundException: oracle.jdbc.OracleDriver` ao registrar o conector

O driver JDBC não está no classpath do Connect. Confira:

```bash
docker compose --env-file .env -f infra/docker-compose.yml \
  exec connect ls -l /kafka/libs/ojdbc11.jar
```

Se o arquivo não existir, o build da imagem não conseguiu baixá-lo (proxy, rede
corporativa, Maven Central bloqueado). Solução manual: baixe
`ojdbc11-23.8.0.25.04.jar` do Maven Central por outro caminho, salve em
`infra/debezium/lib/ojdbc11.jar` e troque a linha `ADD` do
`infra/debezium/Dockerfile` por:

```dockerfile
COPY lib/ojdbc11.jar /kafka/libs/ojdbc11.jar
```

Depois: `make build && make up`.

### `make validate` reclama que os bancos do ClickHouse não existem

Os DDLs de `infra/clickhouse/ddl/` só rodam quando o volume `clickhouse-data`
está vazio. Se você editou um DDL depois do primeiro start, ou o volume ficou de
uma execução anterior:

```bash
make reset && make up
```

Para aplicar um DDL sem apagar tudo (eles são idempotentes):

```bash
docker compose --env-file .env -f infra/docker-compose.yml \
  exec -T clickhouse clickhouse-client --multiquery \
  < infra/clickhouse/ddl/02_log_raw.sql
```

### Oracle não está em ARCHIVELOG

`make validate` marca isso como ⚠️ no item `f`. A Solução 1 continua
funcionando; só o CDC (Solução 2) fica bloqueado, porque o LogMiner exige
ARCHIVELOG.

Corrija sem destruir o ambiente:

```bash
make archivelog      # reinicia só a instância Oracle; os volumes ficam intactos
make validate        # o item 'f' deve passar sem avisos
```

Ambientes novos já saem em ARCHIVELOG — o init faz isso automaticamente. Este
comando existe para ambientes que subiram antes da correção. Detalhes e o
porquê da abordagem em [`docs/decisoes.md`](docs/decisoes.md), seção 5.

### O conector registra mas não produz eventos

Nesta ordem:

```bash
./scripts/register-connector.sh --status    # o task está RUNNING ou FAILED?
make logs s=connect                         # a exceção aparece aqui
```

Causas mais comuns: banco fora de ARCHIVELOG, supplemental logging ausente na
`PROC`, ou grants faltando no `c##dbzuser` — os três são checados no item `f`
do `make validate`.

### Os scripts `.sh` não executam no Windows

Use Git Bash ou WSL. Se der `Permission denied`:

```bash
chmod +x scripts/*.sh infra/oracle/init/*.sh
```

---

## Próximos passos

Esta sessão entregou a **Frente A — Infraestrutura**. As outras duas são
desenvolvidas em sessões separadas, sobre esta base, sem precisar tocar em
infraestrutura:

| Frente | Escopo | Onde começa |
|---|---|---|
| **B — `log-writer`** | Cliente JDBC (Java 8) que a `LogPs` vai chamar: batching, tratamento de falha, e o benchmark de escrita contra o Oracle | `log-writer/` — implementado, 57 testes verdes; ver `log-writer/README.md` |
| **C — Pipeline CDC** | Ajuste fino do conector, validação fim a fim, medição de latência e completude | `infra/debezium/connector-proc.json` + `validacao/03_consultas_cdc.sql` |

O que a Frente A garante para elas:

- ClickHouse de pé, com os dois bancos, as tabelas, a MV e o usuário de
  aplicação;
- Oracle com o schema `PROJUDI`, dados de exemplo e **toda** a preparação de CDC
  (ARCHIVELOG, `c##dbzuser` com os grants, supplemental logging);
- Kafka e Kafka Connect com o plugin Oracle do Debezium e o driver JDBC no
  classpath;
- `./scripts/register-connector.sh` funcionando;
- `make validate` como critério objetivo de "o ambiente está são".

### Estado da validação

**Frente A homologada.** `make validate` num ambiente criado do zero:

```
32 passaram   0 falharam   0 avisos
```

Cobre ClickHouse (6 DDLs, MATERIALIZED VIEW, usuário de aplicação com escrita e
leitura), Oracle (schema `PROJUDI`, 43 colunas da `PROC`, seed, ARCHIVELOG,
supplemental logging, datafiles no volume), Kafka, e Kafka Connect com o plugin
Oracle do Debezium e o driver `ojdbc11` no classpath.

O detalhamento, e o registro dos quatro defeitos que só a execução real
revelou, estão em [`docs/decisoes.md`](docs/decisoes.md), seção 16.
