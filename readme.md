# RotaCerta — Contêineres, Resiliência e Observabilidade

Implementação de referência do estudo de caso da Aula 3 (Unidade 4): um serviço de cotação de frete que consulta transportadoras parceiras e **não deixa a lentidão de uma delas derrubar o sistema**.

## Sumário

1. [Contexto e o incidente](#contexto-e-o-incidente)
2. [Stack](#stack)
3. [Arquitetura](#arquitetura)
4. [Fluxo de uma cotação](#fluxo-de-uma-cotação)
5. [Padrões de resiliência](#padrões-de-resiliência)
6. [Criação de pedido](#criação-de-pedido)
7. [Health checks](#health-checks)
8. [Observabilidade](#observabilidade)
9. [Contêineres e Kubernetes](#contêineres-e-kubernetes)
10. [Endpoints](#endpoints)
11. [Executar o projeto](#executar-o-projeto)
12. [Simulando falhas](#simulando-falhas)

## Contexto e o incidente

A RotaCerta oferece a lojas virtuais consulta de frete, registro de pedidos e escolha de transportadora. Processa ~400 mil cotações/hora, com picos 10x maiores em datas promocionais.

**Incidente:** a API da TransVale passou a responder em até 30 s (normal: 200 ms). Sem timeout, as threads do serviço de Cotação se esgotaram e derrubaram o serviço de Pedidos — falha em cascata de quase 2 horas.

```mermaid
flowchart LR
    subgraph ANTES["❌ Antes: falha em cascata"]
        direction LR
        A1[Pedidos] -->|sem timeout| B1[Cotação]
        B1 -->|30 s de espera| C1[TransVale 🐌]
        B1 -. threads esgotadas .-> A1
    end
    subgraph DEPOIS["✅ Depois: falha contida"]
        direction LR
        A2[Pedidos] -->|timeout 1,2 s| B2[Cotação]
        B2 -->|timeout 800 ms + CB + bulkhead| C2[TransVale 🐌]
        B2 -->|ok| D2[Rápida]
        B2 -->|ok| E2[Econômica]
        B2 -. fallback .-> F2[(Tabela de referência)]
    end
```

## Stack

- Java 17+, Spring Boot 3.x, Maven (multi-módulo)
- Resilience4j: timeout (TimeLimiter), retry, circuit breaker, bulkhead
- Micrometer + Micrometer Tracing (OpenTelemetry/OTLP) + Actuator + Prometheus
- Docker multi-estágio, Kubernetes (Deployment, Service, HPA), Jaeger via Compose

## Arquitetura

| Módulo | Porta | Responsabilidade |
|---|---|---|
| `cotacao-frete-service` | 8081 | Consulta as 3 transportadoras em paralelo, aplica resiliência e rate limit por loja |
| `pedidos-service` | 8082 | Registra pedidos validando o frete no serviço de cotação (armazenamento em memória) |
| `transportadoras-stub` | 8083 | Simula TransVale, Rápida e Econômica, com atraso/falha configuráveis |
| Jaeger | 16686 / 4318 | Visualização de traces (via Compose) |

```mermaid
flowchart LR
    Loja([🛒 Loja virtual])
    subgraph RotaCerta
        P[pedidos-service<br/>:8082]
        C[cotacao-frete-service<br/>:8081]
        R[(ReferenceRateTable<br/>fallback)]
    end
    subgraph Parceiros["Transportadoras (stub :8083)"]
        T1[TransVale]
        T2[Rápida]
        T3[Econômica]
    end
    J[(Jaeger / OTLP)]
    Prom[(Prometheus)]

    Loja -->|POST /v1/pedidos| P
    Loja -->|GET /v1/cotacoes| C
    P -->|GET /v1/cotacoes<br/>timeout 1,2 s| C
    C --> T1
    C --> T2
    C --> T3
    C -.-> R
    P -. traces .-> J
    C -. traces .-> J
    C -. métricas .-> Prom
```

## Fluxo de uma cotação

O serviço dispara as 3 consultas em paralelo e escolhe o menor preço, **preferindo cotações reais** às estimadas.

```mermaid
sequenceDiagram
    autonumber
    participant L as Loja
    participant C as cotacao-frete-service
    participant RL as Rate limiter (50 req/s por loja)
    participant T as TransVale
    participant R as Rápida
    participant E as Econômica
    L->>C: GET /v1/cotacoes?origem&destino&peso (X-Loja-Id)
    C->>RL: allow(lojaId)?
    alt limite excedido
        RL-->>L: 429 Too Many Requests
    else permitido
        par em paralelo (cada uma com sua resiliência)
            C->>T: consulta
        and
            C->>R: consulta
        and
            C->>E: consulta
        end
        T-->>C: cotação (ou fallback estimada)
        R-->>C: cotação
        E-->>C: cotação
        C->>C: escolhe o menor preço (reais antes de estimadas)
        C-->>L: 200 { melhor, cotacoes[] }
    end
```

## Padrões de resiliência

Cada transportadora tem seus **próprios** circuit breaker, retry, bulkhead e timeout — a lentidão de uma não afeta as outras.

| Padrão | Configuração |
|---|---|
| **Timeout** | 800 ms por chamada a transportadora (`PARTNER_TIMEOUT_MS`); 1,2 s de Pedidos → Cotação |
| **Retry** | Máx. 2 tentativas (GET idempotente), backoff exponencial 100 ms → 200 ms → 400 ms (teto) + jitter de até 50% |
| **Circuit Breaker** | Janela de 20 chamadas; abre com ≥ 50% de falhas; aberto por 30 s; half-open com 10 chamadas de teste; transição automática |
| **Fallback** | Tabela de frete de referência, resposta marcada com `"estimada": true` |
| **Bulkhead** | Máx. 40 chamadas simultâneas por transportadora (sem espera) + pool de threads dedicado por parceiro |
| **Rate limiting** | 50 req/s por loja (`X-Loja-Id`), retornando HTTP 429 |

### Ordem de composição das camadas

```mermaid
flowchart LR
    REQ([Chamada]) --> CB
    subgraph CB[Circuit Breaker]
        subgraph RT[Retry + backoff + jitter]
            subgraph BH[Bulkhead 40]
                subgraph TL[Timeout 800 ms]
                    HTTP[[HTTP → transportadora]]
                end
            end
        end
    end
    CB -->|sucesso| OK([Cotação real])
    CB -->|exceção: circuito aberto,<br/>bulkhead cheio ou falha| FB[Fallback<br/>tabela de referência]
    FB --> EST([Cotação estimada=true])
```

### Fluxo de decisão de uma chamada

```mermaid
flowchart TD
    S([Nova chamada à transportadora]) --> Q1{Circuito<br/>aberto?}
    Q1 -- Sim --> FB[Fallback: tabela de referência<br/>estimada = true]
    Q1 -- Não --> Q2{Bulkhead<br/>com vaga?}
    Q2 -- Não, 40 em uso --> FB
    Q2 -- Sim --> CALL[Chamada HTTP<br/>timeout 800 ms]
    CALL --> Q3{Sucesso?}
    Q3 -- Sim --> OK[Cotação real]
    Q3 -- "Falha / timeout / HTTP não-2xx" --> Q4{Tentativas<br/>restantes?}
    Q4 -- Sim --> W[Espera 100 ms + jitter] --> Q2
    Q4 -- Não --> REG[Registra falha no circuit breaker] --> FB
    OK --> REGOK[Registra sucesso no circuit breaker]
```

### Retry com backoff e jitter

```mermaid
sequenceDiagram
    participant C as Cotação
    participant T as Transportadora
    C->>T: Tentativa 1 (timeout 800 ms)
    T--xC: Erro / timeout
    Note over C: espera 100 ms + jitter (0–50 ms)
    C->>T: Tentativa 2 (timeout 800 ms)
    alt sucesso
        T-->>C: 200 OK → cotação real
    else falha novamente
        T--xC: Erro / timeout
        Note over C: máx. 2 tentativas → falha contabilizada no CB<br/>→ fallback estimada=true
    end
```

> O retry fica **dentro** do circuit breaker: o CB contabiliza o resultado final (após as tentativas), não cada tentativa individual.

### Máquina de estados do Circuit Breaker

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: ≥ 50% de falhas em 20 chamadas
    OPEN --> HALF_OPEN: após 30 s (automático)
    HALF_OPEN --> CLOSED: chamadas de teste (10) com taxa de falha < 50%
    HALF_OPEN --> OPEN: chamadas de teste (10) com falha ≥ 50%

    CLOSED: CLOSED<br/>chamadas normais<br/>falhas contabilizadas
    OPEN: OPEN<br/>rejeita imediatamente<br/>usa fallback (sem esperar)
    HALF_OPEN: HALF_OPEN<br/>permite 10 chamadas de teste
```

### Cenário: TransVale passa a responder em 30 s

```mermaid
sequenceDiagram
    autonumber
    participant L as Loja
    participant C as Cotação
    participant T as TransVale 🐌 (30 s)
    participant O as Rápida / Econômica
    participant F as Tabela de referência

    rect rgb(255, 240, 230)
    Note over C,T: Fase 1 — circuito FECHADO
    L->>C: cotação
    C->>T: consulta
    T--xC: timeout em 800 ms (×2 com retry)
    C->>F: fallback
    C->>O: consulta (paralelo, normal)
    C-->>L: 200 (TransVale estimada=true, demais reais)
    end

    rect rgb(255, 225, 225)
    Note over C: Falhas atingem 50% em 20 chamadas → circuito ABRE
    end

    rect rgb(230, 245, 230)
    Note over C,T: Fase 2 — circuito ABERTO (30 s)
    L->>C: cotação
    C--xT: nem chama (CallNotPermitted)
    C->>F: fallback imediato (~0 ms)
    C->>O: consulta
    C-->>L: 200 rápido, sem esgotar threads
    end

    rect rgb(230, 235, 255)
    Note over C,T: Fase 3 — HALF_OPEN após 30 s
    C->>T: 10 chamadas de teste
    alt TransVale recuperada
        Note over C: volta a CLOSED
    else ainda lenta
        Note over C: volta a OPEN
    end
    end
```

### Isolamento por bulkhead

```mermaid
flowchart LR
    REQ([Requisições de cotação]) --> C[cotacao-frete-service]
    C --> B1["Bulkhead TransVale<br/>40 slots · pool dedicado 🔴 saturado"]
    C --> B2["Bulkhead Rápida<br/>40 slots · pool dedicado 🟢"]
    C --> B3["Bulkhead Econômica<br/>40 slots · pool dedicado 🟢"]
    B1 -->|cheio → fallback| FB[(Referência)]
    B2 --> T2[Rápida]
    B3 --> T3[Econômica]
    B1 --> T1[TransVale 🐌]
```

## Criação de pedido

`pedidos-service` valida o frete com timeout de 1,2 s. Como a cotação sempre responde (real ou estimada), o pedido só falha se a própria Cotação estiver indisponível — e nesse caso com **504** rápido, sem prender threads.

```mermaid
sequenceDiagram
    autonumber
    participant L as Loja
    participant P as pedidos-service
    participant C as cotacao-frete-service
    L->>P: POST /v1/pedidos {lojaId, origem, destino, peso}
    alt dados inválidos
        P-->>L: 400 Bad Request
    else válido
        P->>C: GET /v1/cotacoes (X-Loja-Id, traceparent) — timeout 1,2 s
        alt resposta dentro de 1,2 s
            C-->>P: melhor cotação + lista
            P->>P: salva pedido (CONFIRMADO)
            P-->>L: 201 Created
        else timeout / indisponível
            P-->>L: 504 Gateway Timeout
        end
    end
```

## Health checks

Lição do incidente: as probes **nunca** consultam parceiros externos. Antes, uma transportadora lenta fazia o health check falhar e as instâncias reiniciavam em sequência sem nenhum benefício.

```mermaid
flowchart LR
    K[Kubernetes] -->|livenessProbe| LV["/actuator/health/liveness<br/>só estado do processo"]
    K -->|readinessProbe| RD["/actuator/health/readiness<br/>só estado local do serviço"]
    RD -. ❌ nunca .-> EXT[Transportadoras externas]
    LV -. ❌ nunca .-> EXT
```

## Observabilidade

```mermaid
flowchart LR
    P[pedidos-service] -->|traceparent W3C| C[cotacao-frete-service]
    C -->|traceparent| T[Transportadoras]
    P -->|OTLP :4318| J[(Jaeger :16686)]
    C -->|OTLP :4318| J
    C -->|/actuator/prometheus| M[(Prometheus)]
    C -->|/actuator/circuitbreakers<br/>/actuator/circuitbreakerevents| CB[Estado dos CBs]
```

- Um trace por requisição, com span `carrier.quote` (tag `peer.service`) por transportadora, ponta a ponta Pedidos → Cotação → parceiro.
- Logs do Spring trazem `traceId`/`spanId` via MDC.
- Quatro sinais de ouro (latência, tráfego, erros, saturação) em `/actuator/metrics` e `/actuator/prometheus`.

## Contêineres e Kubernetes

- Dockerfile multi-estágio por serviço (JDK compila, JRE executa, usuário sem privilégios).
- Configuração por variáveis de ambiente (URLs, timeouts, atrasos do stub).
- Manifestos em `k8s/`: Deployments (requests/limits e probes), Services e HPA.

```mermaid
flowchart TB
    subgraph K8s[Cluster Kubernetes]
        SVCP[Service pedidos] --> DP[Deployment pedidos]
        SVCC[Service cotacao-frete] --> DC[Deployment cotacao-frete]
        SVCT[Service transportadoras-stub] --> DT[Deployment stub]
        HPA["HPA<br/>4–40 réplicas · alvo 60% CPU"] -. escala .-> DC
        HPA -. escala .-> DP
        DP --> SVCC
        DC --> SVCT
    end
```

## Endpoints

| Serviço | Verbo | Rota | Comportamento |
|---|---|---|---|
| cotacao-frete-service | GET | `/v1/cotacoes?origem=&destino=&peso=` | Melhor cotação entre as 3 transportadoras (ou estimada via fallback); header `X-Loja-Id` aplica o rate limit |
| pedidos-service | POST | `/v1/pedidos` | Valida a cotação (timeout 1,2 s) e registra o pedido |
| pedidos-service | GET | `/v1/pedidos/{id}` | Consulta um pedido |
| cotacao-frete-service | GET | `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents` | Estado e eventos dos circuit breakers |
| todos | GET | `/actuator/health`, `/actuator/prometheus` | Saúde e métricas |

## Executar o projeto

Requisitos: Java 17+, Maven 3.9+ e Docker com Docker Compose.

```bash
mvn test
docker compose up --build
```

- Cotação: `GET http://localhost:8081/v1/cotacoes?origem=SP&destino=RJ&peso=2` (envie `X-Loja-Id` para o limite por loja).
- Pedidos: `POST http://localhost:8082/v1/pedidos` com `{"lojaId":"loja-1","origem":"SP","destino":"RJ","peso":2}`.
- Traces: `http://localhost:16686` (Jaeger).

## Simulando falhas

```bash
TRANSVALE_DELAY_MS=30000 docker compose up --build   # TransVale lenta
TRANSVALE_FAIL=true docker compose up --build        # TransVale com erro
```

O stub aceita `*_DELAY_MS` e `*_FAIL` para `TRANSVALE`, `RAPIDA` e `ECONOMICA`. O teste `SlowCarrierResilienceTest` simula a resposta de 30 s e verifica que o circuito abre, o fallback é usado e as demais transportadoras continuam respondendo.

Para observar: dispare várias cotações e acompanhe `/actuator/circuitbreakers` — o circuito da `transvale` passa de `CLOSED` para `OPEN`, e as respostas passam a trazer `"estimada": true` para ela, sem aumento de latência.
