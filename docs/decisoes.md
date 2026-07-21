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

> **Esta decisão foi corrigida depois da primeira execução real.** A versão
> original dizia para usar as variáveis de ambiente `ENABLE_ARCHIVELOG=true` e
> `ENABLE_FORCE_LOGGING=true` no compose. **Isso não funciona:** essas variáveis
> não existem no entrypoint do `gvenzl/oracle-free`. O `make validate` de
> 2026-07-21 acusou `log_mode = NOARCHIVELOG` com as variáveis definidas, e a
> inspeção do código da imagem confirmou que elas nunca são lidas.
>
> Fica registrado como erro em vez de ser apagado: é exatamente o tipo de
> suposição que só cai em execução, e o histórico da correção vale mais que uma
> decisão que finge ter nascido certa.

**Escolhido:** a sequência explícita, em
`infra/oracle/init/sql/05_enable_archivelog.sql`:

```sql
SHUTDOWN IMMEDIATE;
STARTUP MOUNT;
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER DATABASE FORCE LOGGING;
ALTER PLUGGABLE DATABASE ALL OPEN;
ALTER PLUGGABLE DATABASE ALL SAVE STATE;
```

Três detalhes que não são óbvios:

- **Precisa de conexão local** (`sqlplus / as sysdba` dentro do container).
  `STARTUP` não funciona através do listener.
- **A decisão de executar fica no shell**, não no SQL: `SHUTDOWN` e `STARTUP`
  são comandos do SQL*Plus e não podem ser condicionados dentro de um bloco
  PL/SQL. O orquestrador consulta `v$database.log_mode` e só chama o script se
  precisar.
- **Depois do restart o listener leva alguns segundos** para registrar de novo
  o serviço do PDB. Sem esperar, o script seguinte falharia com ORA-12514 e o
  sintoma não apontaria para a causa. Há um laço de espera nos dois pontos que
  executam a sequência.

Aplicado em dois lugares, com o mesmo `.sql`:

| Onde | Quando |
|---|---|
| `infra/oracle/init/01_init_projudi.sh` | ambientes novos, no primeiro start |
| `scripts/enable-archivelog.sh` (`make archivelog`) | ambiente já de pé, sem `make reset` |

O segundo existe para não obrigar a destruir os volumes e esperar minutos só
para trocar o `log_mode`.

**Não falha em silêncio:** se a sequência não funcionar, o init imprime um bloco
de aviso no log do container, e o item `f` do `make validate` marca o problema.
A Solução 1 não depende de archivelog — só a Solução 2 fica bloqueada.

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

O ambiente foi construído numa máquina Windows **sem Docker e sem possibilidade
de instalá-lo**: conta de domínio corporativo sem privilégio administrativo, com
WSL2, VirtualMachinePlatform, HypervisorPlatform e Hyper-V desabilitados na
imagem do sistema. Não foi possível executar `setup.sh`, `make up` nem
`make validate` na sessão em que o código foi escrito.

A primeira execução real cabe ao ambiente de referência do projeto — ver
`docs/ambientes.md`, seção 6. **Enquanto `make validate` não terminar com
`0 falharam` numa execução real, considere a Frente A como não homologada.**

O que **foi** verificado estaticamente:

- sintaxe de todos os scripts `.sh` (`bash -n`) e do `Makefile`;
- YAML do compose e XML das configs do ClickHouse bem formados;
- `pom.xml` válido e as versões de artefato existentes no Maven Central
  (`ojdbc11:23.8.0.25.04`, `clickhouse-jdbc:0.7.2`);
- existência de todas as tags de imagem nos respectivos registries;
- contagem de colunas: 43 na `PROC` (Oracle e ClickHouse), 47 na `proc_cdc`
  (43 + 4 de metadata), 13 na `log_raw`;
- portas 1521, 8080, 8083, 8123, 9000, 9092 e 29092 livres na máquina.

### Estado da validação

**Frente A homologada em 2026-07-21.** `make validate` num ambiente criado do
zero (`make reset && make up`):

```
32 passaram   0 falharam   0 avisos
```

Executado numa máquina Linux (WSL) com 8 GB de RAM no host e apenas 3 GB
disponíveis ao daemon Docker — abaixo do mínimo recomendado de 12 GB, e ainda
assim suficiente para a pilha completa. O aviso do `setup.sh` continua correto
como recomendação, mas o piso real é mais baixo que o documentado.

Confirmado em execução, item a item:

| Área | Resultado |
|---|---|
| `setup.sh` completo, incluindo o build da imagem do Connect com o `ojdbc11` baixado do Maven Central | ✅ |
| Os 6 DDLs do ClickHouse aplicados no primeiro start | ✅ |
| `proc_cdc_mv` — a MATERIALIZED VIEW com `JSONExtract`, `accurateCastOrNull` e `toDateTime64` sobre `Nullable` | ✅ |
| `proc_cdc` com exatamente 47 colunas | ✅ |
| Usuário `projudi_app` criado e com ciclo completo de escrita e leitura em `log_raw` | ✅ |
| Oracle: schema `PROJUDI`, as 43 colunas da `PROC`, seed nas 3 tabelas | ✅ |
| **Todos os datafiles dentro do volume `oracle-data`** (decisão 18) | ✅ |
| Kafka respondendo, tópicos internos do Connect criados | ✅ |
| Kafka Connect com o plugin `OracleConnector` e o `ojdbc11` no classpath | ✅ |
| **Oracle em ARCHIVELOG, aplicado pelo init** (decisão 5) | ✅ |
| Supplemental logging de banco e `(ALL) COLUMNS` na `PROJUDI.PROC` | ✅ |
| Usuário `c##dbzuser` existente | ✅ |

### O que a validação encontrou pelo caminho

Quatro suposições caíram apenas em execução real, nenhuma detectada pela
verificação estática:

1. **`ENABLE_ARCHIVELOG` seria interpretada pela imagem** — não é (decisão 5).
2. **`healthy` do Docker significaria "pronto para consulta"** — não significa;
   consultas na janela de carregamento de metadados voltavam vazias e o
   relatório chegou a imprimir "banco X NÃO existe" duas linhas acima de
   "tabela de X existe".
3. **Silenciar a saída de um comando seria inócuo** — esconde prompts
   interativos e trava o script indefinidamente.
4. **Nome de datafile relativo seria equivalente a absoluto** — não é; o
   arquivo vai para a camada gravável da imagem e o banco morre na primeira
   recriação de container (decisão 18).

A quarta é a mais instrutiva: sobreviveu à revisão estática **e** a uma execução
bem-sucedida de 30 itens, porque só se manifesta num evento de ciclo de vida —
recriação de container — que nenhum teste anterior havia exercitado.

### O que continua sem execução

| Item | Escopo |
|---|---|
| `scripts/enable-archivelog.sh` (`make archivelog`) | Nunca rodou com sucesso: o caminho validado foi o do init, num ambiente novo. Convenência para ambientes antigos; sem uso previsto agora. |
| `scripts/register-connector.sh` | Primeira tarefa da Frente C. |
| Pipeline CDC fim a fim (Oracle → Kafka → ClickHouse) | Frente C. Não faz parte do critério de aceite da Frente A. |

O caminho fim a fim do CDC (Oracle → Kafka → ClickHouse) é escopo da Frente C e
**não** faz parte do critério de aceite desta sessão.

---

## 17. Ambiente em container (`.devcontainer/`)

A máquina de desenvolvimento principal não roda containers (seção 16), e a
alternativa pessoal disponível tem 8 GB de RAM — insuficiente para a pilha
completa. Sem uma saída, a Frente A ficaria escrita mas nunca executada.

**Escolhido:** um `.devcontainer/devcontainer.json` com a feature
`docker-in-docker`, que faz o repositório rodar em **GitHub Codespaces** (pelo
navegador, sem instalar nada) ou em qualquer host Linux via VS Code Dev
Containers. O `docker-compose.yml` não muda em nada: o devcontainer apenas o
embrulha.

Detalhes que não são óbvios:

- **`hostRequirements` pede 4 núcleos / 16 GB / 32 GB de disco.** O tipo padrão
  de 2 núcleos não comporta Oracle + Kafka + Connect + ClickHouse. E o disco
  precisa ser exatamente 32 GB: pedir mais empurra a seleção para o tipo de 8
  núcleos, que consome a cota do Codespaces duas vezes mais rápido sem nenhum
  ganho.
- **Java 8 e Maven vêm instalados**, para que a Frente B comece sem preparo.
- **O download das imagens não é feito no `postCreateCommand`.** Levaria vários
  minutos, e um timeout na criação do Codespace é bem mais difícil de
  diagnosticar do que um `make setup` que falha com mensagem clara.

**Efeito colateral positivo, e o motivo de isso valer para a defesa:** com o
repositório público e o devcontainer versionado, qualquer avaliador reproduz o
ambiente inteiro em um clique, com a própria cota do GitHub. O trabalho deixa de
depender da descrição de um ambiente e passa a ser executável por terceiros.

**Multi-arquitetura:** todas as cinco imagens publicam `linux/amd64` e
`linux/arm64`, verificado nos registries. Windows, Linux, Mac Intel e Mac Apple
Silicon rodam nativamente, sem emulação — o que permite que a dupla trabalhe em
máquinas diferentes.

A restrição que sobra está em `docs/ambientes.md`, seção 5: **os números finais
de benchmark precisam sair de um único ambiente de referência.** Ambientes
diferentes produzem medições que não se combinam.

---

## 18. Datafiles precisam de caminho absoluto dentro do volume

Erro de construção descoberto em 2026-07-21, com o ambiente já validado e em
uso. Custou a perda total de um banco de desenvolvimento.

**O que estava errado.** As três tablespaces criadas pelo init usavam nome de
arquivo relativo:

```sql
CREATE TABLESPACE logminer_tbs DATAFILE 'logminer_tbs_root.dbf' SIZE 25M ...;
```

O Oracle resolve nome relativo a partir de `$ORACLE_HOME/dbs` — que fica na
**camada gravável da imagem**, não no volume nomeado `oracle-data` montado em
`/opt/oracle/oradata`.

**Por que passou despercebido.** Um `docker compose restart` preserva a camada
gravável, e tudo funciona. O `make validate` completo passou com 30 itens. O
problema só aparece quando o container é **recriado** — `docker compose down`
seguido de `up`, troca de versão de imagem, ou reinício do Docker Desktop. Aí a
camada gravável é descartada, os datafiles somem, e o banco entra em loop de
restart:

```
ORA-01157: cannot identify/lock data file 27
ORA-01110: data file 27: '.../dbhomeFree/dbs/logminer_tbs_root.dbf'
```

Sem recuperação prática: a tablespace faz parte do dicionário, o arquivo não
existe mais, e o banco não abre para se consertar.

**Correção.** O diretório é derivado em tempo de execução, do datafile da
SYSTEM — que por construção está dentro do volume:

```sql
COLUMN dbf_dir NEW_VALUE dbf_dir NOPRINT
SELECT SUBSTR(file_name, 1, INSTR(file_name, '/', -1)) AS dbf_dir
FROM   dba_data_files WHERE tablespace_name = 'SYSTEM' AND ROWNUM = 1;

CREATE TABLESPACE logminer_tbs DATAFILE '&dbf_dir.logminer_tbs.dbf' ...;
```

Derivado, e não escrito à mão, porque o layout muda entre versões da imagem — o
caminho já mudou de `dbhomeFree/23ai` para `dbhomeFree/26ai` durante este
próprio trabalho. Antes do `CREATE`, um bloco PL/SQL aborta o init se o
diretório não estiver sob `/opt/oracle/oradata` — melhor falhar na criação do
que entregar um banco que morre no primeiro `down`.

**Detecção.** O `make validate` passa a conferir:

```sql
SELECT count(*) FROM cdb_data_files
 WHERE file_name NOT LIKE '/opt/oracle/oradata/%';   -- tem que ser 0
```

**Lição para o relatório.** Esta é a falha mais instrutiva do trabalho: passou
por revisão estática completa, passou por uma execução bem-sucedida de 30 itens,
e só apareceu num evento de ciclo de vida — recriação de container — que nenhum
teste anterior havia exercitado. Persistência em container não é sobre o que
funciona enquanto o container está de pé; é sobre o que sobrevive quando ele
deixa de existir.
