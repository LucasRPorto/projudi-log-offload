# Registro de decisões técnicas

Decisões tomadas durante a construção da infraestrutura (Frente A) que **não**
estavam definidas no escopo, e por quê. Formato: decisão → alternativas
consideradas → motivo.

Sessão de 2026-07-21.

---

## 1. Versões de imagem

| Serviço | Versão fixada | Motivo |
|---|---|---|
| ClickHouse | `clickhouse/clickhouse-server:25.8` | Linha LTS. Havia versões 26.x disponíveis, mas para um trabalho que vai ser defendido meses depois interessa a versão com suporte longo, não a mais nova. |
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | `slim` corta os componentes que o MVP não usa (APEX, exemplos). `faststart` traz o banco já criado na imagem: o primeiro start cai de ~15 min para ~2-3 min, e o ciclo `make reset` deixa de ser proibitivo. |
| Kafka | `confluentinc/cp-kafka:8.0.6` | Ver decisão 2. |
| Kafka Connect | `quay.io/debezium/connect:3.6.0.Final` | Última `.Final` da linha 3.6, contemporânea do Kafka 4.x que a CP 8.0 embarca. |
| Kafka UI | `kafbat/kafka-ui:v1.5.0` | Ver decisão 3. |
| Driver JDBC Oracle | `ojdbc11:23.8.0.25.04` | Ver decisão 4. |

Todas as versões são parametrizadas no `.env` — trocar qualquer uma é editar uma
linha, não caçar string no compose.

---

## 2. Kafka: `confluentinc/cp-kafka` em vez de `apache/kafka`

O escopo permitia qualquer um dos dois. Escolhido o da Confluent por um motivo
prático: a imagem `apache/kafka` não declara `/var/lib/kafka/data` no
`Dockerfile`, então um volume nomeado montado ali é criado pertencendo ao
`root`, enquanto o broker roda como uid 1000 — e o start falha com erro de
permissão. Contornar isso exigiria rodar o broker como root ou fazer malabarismo
de `chown` no entrypoint.

A imagem `cp-kafka` já cria o diretório com o dono correto. Ambas rodam KRaft
puro (sem ZooKeeper), que era o requisito real.

---

## 3. Kafka UI: `kafbat/kafka-ui` em vez de `provectuslabs/kafka-ui`

O escopo indicava a imagem da Provectus. O projeto foi descontinuado — a última
release é a `v0.7.2`, de 2023, com Kafka clients 3.5. O `kafbat/kafka-ui` é o
fork mantido pelos mesmos desenvolvedores, com suporte aos brokers Kafka 4.x que
este ambiente usa. Mesma interface, mesmas variáveis de configuração.

É um serviço opcional: se ele falhar, nada mais no ambiente é afetado, e
`make validate` não o considera.

---

## 4. Driver JDBC da Oracle: baixado no build da imagem

O conector Oracle do Debezium precisa do `ojdbc` no classpath e a imagem oficial
não o distribui.

**Escolhido:** um `Dockerfile` derivado (`infra/debezium/Dockerfile`) que baixa o
jar do Maven Central e o instala em `/kafka/libs/`.

Alternativas descartadas:

- *Bind mount de um jar baixado à mão.* Se o arquivo não existir no host, o
  Docker cria um **diretório** com aquele nome, e o erro resultante não tem
  relação óbvia com a causa. Além disso obrigaria um passo manual antes do
  primeiro `make up`.
- *Colocar o jar no diretório do plugin* (`/kafka/connect/debezium-connector-oracle/`).
  Montar um volume ali esconderia os jars do próprio conector.

`/kafka/libs` é o classpath do sistema: o classloader isolado de plugin do
Connect delega classes de driver JDBC ao classloader pai, então o driver fica
visível para o conector Oracle e para qualquer outro que venha a precisar.

**Sobre a licença:** o `ojdbc11` é publicado pela própria Oracle no Maven Central
sob a *Oracle Free Use Terms and Conditions* (FUTC), que permite download e uso
sem custo e sem conta. É o mesmo motivo pelo qual a imagem de banco usada aqui é
a `gvenzl/oracle-free` e não a oficial da Oracle (essa sim exige login).

Se a rede bloquear o `repo1.maven.org`, o `setup.sh` falha com mensagem
explícita e o README traz o procedimento manual.

---

## 5. ARCHIVELOG

O Debezium Oracle Connector usa LogMiner, que **exige** o banco em modo
ARCHIVELOG. A imagem `gvenzl/oracle-free` sobe em NOARCHIVELOG por padrão.

**Escolhido:** as variáveis `ENABLE_ARCHIVELOG=true` e
`ENABLE_FORCE_LOGGING=true` no compose, que a própria imagem interpreta e aplica
antes de liberar o banco.

Alternativa descartada: fazer `SHUTDOWN IMMEDIATE` / `STARTUP MOUNT` /
`ALTER DATABASE ARCHIVELOG` de dentro de um script de init. Funciona, mas
derruba o banco no meio do entrypoint da imagem, o que interfere no healthcheck
e deixa a inicialização frágil.

**Não falha em silêncio:** `infra/oracle/init/01_init_projudi.sh` consulta
`v$database.log_mode` logo no início. Se não estiver em ARCHIVELOG, imprime um
bloco de aviso no log do container com o procedimento manual, e segue — a
Solução 1 não depende de archivelog, só a Solução 2. `make validate` repete a
verificação (item `f`) e marca como aviso.

Procedimento manual, se necessário:

```sql
-- dentro do container projudi-oracle, como sysdba
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER PLUGGABLE DATABASE ALL OPEN;
```

**Consequência operacional:** com ARCHIVELOG ligado, o Oracle acumula archived
logs até encher a FRA. Num ambiente de laboratório de vida curta isso não
incomoda; se o ambiente ficar semanas de pé, é preciso limpar
(`RMAN> DELETE ARCHIVELOG ALL;`) ou aumentar `db_recovery_file_dest_size`.

---

## 6. Supplemental logging: `(ALL) COLUMNS` na `PROC`

Sem supplemental logging, o redo guarda apenas o ROWID e as colunas alteradas —
um `UPDATE` de uma coluna produziria um evento com as outras 42 nulas, e o
histórico da Solução 2 seria inútil.

Aplicado em dois níveis, como a documentação do Debezium pede:

- `ALTER DATABASE ADD SUPPLEMENTAL LOG DATA` no `CDB$ROOT` (mínimo, nível banco);
- `ALTER TABLE PROJUDI.PROC ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` no PDB.

O custo é redo maior a cada `UPDATE` na `PROC` — e é justamente um dos números
que a Frente C deve medir, porque em produção esse custo recai sobre o banco
transacional que a Solução 2 pretende aliviar. Aplicado a **uma** tabela apenas.

---

## 7. Formato do Kafka engine: `JSONAsString`, não `JSONEachRow`

O escopo deixava a escolha em aberto. **Escolhido `JSONAsString` + `JSONExtract`
na MATERIALIZED VIEW.**

Com `JSONEachRow` seria preciso declarar `before` e `after` como `Tuple` de 43
campos cada, dentro da definição da tabela Kafka. O problema não é a verbosidade:
é que qualquer mensagem que não case exatamente com esse tipo faz o ClickHouse
descartar o **bloco inteiro** e avançar o offset — perda silenciosa de eventos.
Uma coluna nova na `PROC`, um campo ausente, e o histórico ganha um buraco que
ninguém percebe.

Com `JSONAsString` a mensagem entra como texto opaco numa coluna `String` e o
desmembramento acontece na MV, campo a campo. Campo ausente vira `NULL` naquela
coluna, não erro.

Custo: reparsear o JSON na MV. Mitigado pelo fato de o ClickHouse deduplicar
subexpressões idênticas no DAG de execução — `JSONExtractRaw(env, 'after')`
aparece 43 vezes no SQL e é avaliado uma vez por linha.

`kafka_handle_error_mode = 'stream'` completa a proteção: mensagem ilegível vai
para as colunas virtuais `_error` / `_raw_message` em vez de derrubar o consumo.

**A MV aceita os dois formatos de envelope.** Com `schemas.enable=false` (o
padrão configurado aqui) o JSON já é o payload; com `schemas.enable=true` ele vem
dentro de `"payload"`. O alias `env` da MV resolve os dois casos, então a Frente C
pode ligar os schemas sem mexer no DDL.

---

## 8. `decimal.handling.mode=double` e `time.precision.mode=connect`

Duas configurações do conector que existem para simplificar a MV:

**`decimal.handling.mode=double`** — o padrão (`precise`) serializa `NUMBER` como
bytes de `BigDecimal` codificados em base64, o que exigiria decodificação binária
dentro do ClickHouse. Com `double`, os valores chegam como número JSON.

Ressalva registrada: `double` tem 53 bits de mantissa (~9×10¹⁵ exato). As colunas
`NUMBER(24)` da `PROC` (`ID_PROC`, `PROC_NUMERO`, …) vêm de sequences e hoje
estão na casa de 10⁹–10¹⁰, com folga de cinco ordens de grandeza. Os valores
monetários `NUMBER(20,2)` são convertidos para `Decimal(20,2)` no destino, então
o armazenamento final é exato — o `double` é só o formato de transporte. A
alternativa (`string`) tornaria o parsing heterogêneo, porque `NUMBER(p,0)` com
p ≤ 18 continuaria chegando como inteiro.

**`time.precision.mode=connect`** — força todos os temporais a epoch em
**milissegundos**. Com o padrão `adaptive`, a unidade varia conforme o tipo da
coluna de origem, e a MV precisaria de uma conversão diferente por coluna. Aqui
todas as 7 colunas `DATE` usam a mesma expressão.

**`log.mining.strategy=online_catalog`** — usa o dicionário de dados corrente em
vez de gravar o dicionário no redo. Muito mais leve, com a limitação de não
acompanhar DDL da tabela capturada. Como a estrutura da `PROC` é fixa no MVP, é
a escolha certa; se a Frente C precisar testar evolução de schema, trocar para
`redo_log_catalog`.

---

## 9. Mapeamento `NUMBER(24) → UInt64`

O escopo pedia `UInt64` com justificativa, ou `Int64` justificado.

Mantido `UInt64`, com a ressalva registrada aqui e no próprio DDL:
**`NUMBER(24)` formalmente excede `UInt64`** (10²⁴−1 contra ~1,84×10¹⁹). Não é um
mapeamento exato.

Foi escolhido assim porque todos os `NUMBER(24)` do schema são identificadores
gerados por sequence, hoje na casa de 10⁹–10¹⁰; o teto de `UInt64` só seria
atingido com ~10¹⁹ registros. As alternativas exatas — `Decimal(24,0)` (16 bytes,
aritmética mais lenta) ou `String` — custariam o dobro de espaço e tirariam
`ID_LOG` / `ID_PROC` da comparação nativa que a chave de ordenação usa.

`UInt` e não `Int` porque são identificadores: nunca negativos, e o tipo sem
sinal documenta essa invariante.

Regra geral aplicada às demais colunas: `NUMBER(p)` → menor inteiro sem sinal que
cobre 10^p − 1. Daí `NUMBER(10) → UInt64` (não cabe em `UInt32`),
`NUMBER(5) → UInt32`, `NUMBER(3) → UInt16`, `NUMBER(1) → UInt8`.

---

## 10. Nulabilidade assimétrica entre `log_raw` e `proc_cdc`

Em `log_raw`, colunas não-`Nullable` sempre que possível: o produtor é a nossa
própria classe Java, que controla o que envia, e `HORA` é a primeira coluna da
chave de ordenação (chave `Nullable` degrada o índice esparso).

Em `proc_cdc`, todas as 43 colunas de negócio são `Nullable` exceto `ID_PROC`. O
produtor ali é o LogMiner, e um evento com coluna ausente — supplemental logging
incompleto, DDL, before-image parcial — faria a MV abortar o bloco inteiro se a
coluna fosse `NOT NULL`. Preferiu-se ingerir com `NULL` e detectar depois a não
ingerir nada.

---

## 11. Usuário de aplicação do ClickHouse criado via SQL

O escopo pedia "usuário de aplicação além do default".

O caminho óbvio seria a variável `CLICKHOUSE_USER` do entrypoint oficial — mas
ela **remove** o usuário `default`, o que contraria o "além do default" e tira a
conveniência do `make ch` sem senha.

**Escolhido:** um arquivo estático em `users.d/` que só habilita
`access_management` para o `default`, e um script `ddl/90_app_user.sh` que cria o
usuário de aplicação via `CREATE USER`, com a senha vinda do `.env`. O arquivo
versionado não carrega segredo, e a senha fica armazenada como hash sha256 no
diretório `access/` do volume.

O script roda por último no `initdb.d` porque os `GRANT` dependem dos bancos já
existirem. Ele deliberadamente **não** usa `set -e` nem `exit`: o entrypoint do
ClickHouse executa `.sh` sem bit de execução via `source`, e um `exit` mataria o
entrypoint inteiro sem mensagem útil.

---

## 12. Init do Oracle: um orquestrador `.sh` chamando `.sql` parametrizados

Dois problemas resolvidos de uma vez:

1. **Senhas.** `PROJUDI` e `c##dbzuser` precisam de senha vinda do `.env`. Um
   `.sql` versionado não lê variável de ambiente; o orquestrador passa as senhas
   como parâmetros posicionais do SQL*Plus (`&1`, `&2`).

2. **Container.** É um banco multitenant. Parte do setup é no `CDB$ROOT` (usuário
   comum, supplemental logging de banco) e parte no PDB (schema, tabelas,
   supplemental logging da tabela). Um `.sql` solto herdaria o container em que a
   imagem resolveu conectar; aqui cada script recebe uma connect string
   **explícita**.

Os `.sql` ficam em `infra/oracle/init/sql/` — subdiretório, para que a imagem não
os execute por conta própria (ela varre apenas o primeiro nível).

Os grants nas views `V_$` dinâmicas são concedidos **um a um**, dentro de um
bloco PL/SQL com tratamento de exceção: a lista exata varia entre versões do
Oracle e do Debezium, e uma view inexistente abortaria o init inteiro. O que
falhar vira aviso visível no log do container — sem mascarar o problema.

---

## 13. Acréscimos ao schema além do especificado

Três itens que não estavam no escopo:

- **`projudi_logs.log_tipo` no ClickHouse.** Sem a dimensão, toda consulta de
  validação precisaria decorar códigos numéricos. `ReplacingMergeTree` porque a
  tabela é recarregada por inteiro, não incrementada.
- **Sequences `PROJUDI.SEQ_LOG` e `PROJUDI.SEQ_PROC` no Oracle.** Não existem em
  produção (lá os IDs vêm da aplicação), mas permitem que as Frentes B e C gerem
  carga de teste sem administrar IDs à mão.
- **Índices na `PROJUDI.LOG`** (`HORA`; `ID_USU, HORA`; `TABELA, ID_TABELA`).
  Espelham os padrões de consulta que a chave de ordenação do ClickHouse atende.
  Sem eles, a comparação de tempo de resposta Oracle × ClickHouse seria desonesta
  — estaria medindo full table scan contra índice.

---

## 14. `log-writer`: Java 8 e clickhouse-jdbc 0.7.2

`java.version` = 8 confirmado no `pom.xml` do repositório `../projudi`. O
artefato precisa rodar dentro daquela aplicação, então compilar para uma versão
mais nova não é opção.

Isso fixa o driver: **`clickhouse-jdbc` 0.7.2 é a última linha compatível com
Java 8** (0.8.x em diante exige JDK mais recente).

Classificador `all` (uber jar sombreado) com exclusão de todas as transitivas: o
classpath do Projudi é grande e antigo, e o risco de conflito de versão de
biblioteca comum (HTTP client, JSON) é real. O jar sombreado elimina a classe
inteira de problema.

---

## 15. `.env` na raiz, compose em `infra/`

O Docker Compose procura o `.env` no diretório do arquivo de compose, não no
diretório de onde é invocado. Com o compose em `infra/`, um `.env` na raiz seria
ignorado silenciosamente.

Todos os pontos de entrada (Makefile e os três scripts) passam
`--env-file .env -f infra/docker-compose.yml` explicitamente. Quem rodar
`docker compose` à mão de dentro de `infra/` **não** vai enxergar as variáveis —
está documentado no topo do `.env.example` e do compose.

---

## 16. Pendências de validação

O ambiente foi construído numa máquina Windows **sem Docker instalado**
(`docker` não está no PATH, Docker Desktop não instalado, WSL sem distribuição).
Não foi possível executar `setup.sh`, `make up` nem `make validate` nesta sessão.

O que **foi** verificado estaticamente:

- sintaxe de todos os scripts `.sh` (`bash -n`) e do `Makefile`;
- YAML do compose e XML das configs do ClickHouse bem formados;
- `pom.xml` válido e as versões de artefato existentes no Maven Central
  (`ojdbc11:23.8.0.25.04`, `clickhouse-jdbc:0.7.2`);
- existência de todas as tags de imagem nos respectivos registries;
- contagem de colunas: 43 na `PROC` (Oracle e ClickHouse), 47 na `proc_cdc`
  (43 + 4 de metadata), 13 na `log_raw`;
- portas 1521, 8080, 8083, 8123, 9000, 9092 e 29092 livres na máquina.

O que **não** foi verificado e precisa de uma execução real:

| Item | Como confirmar |
|---|---|
| `ENABLE_ARCHIVELOG` surte efeito na imagem `faststart` | item `f` do `make validate` |
| Scripts de `initdb.d` executam com o bit de execução vindo do checkout | log do container: `make logs s=oracle` |
| Sintaxe/semântica dos DDLs contra o ClickHouse real | itens `a` e `e` do `make validate` |
| A MV aceita as expressões `JSONExtract` / `accurateCastOrNull` | criação da MV falharia no init; item `a` |
| Build da imagem do Connect com acesso ao Maven Central | `make setup` |
| Compatibilidade cp-kafka 8.0.6 × Debezium 3.6 | item `d` do `make validate` |

O caminho fim a fim do CDC (Oracle → Kafka → ClickHouse) é escopo da Frente C e
**não** faz parte do critério de aceite desta sessão.
