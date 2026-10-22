# RotaCerta — Contêineres, Resiliência e Observabilidade

> Este README é a especificação funcional e arquitetural para implementação de uma aplicação baseada no estudo de caso da Aula 3 (Unidade 4). Ele **não contém código** — a implementação deve ser feita por outra IA/desenvolvedor a partir destas instruções.

## Stack obrigatória

- **Linguagem: Java** (17 ou 21 LTS)
- **Framework: Spring Boot 3.x**
- Build: Maven ou Gradle
- Resiliência: **Resilience4j** (timeout, retry, circuit breaker, bulkhead, rate limiter) — integração via `resilience4j-spring-boot3`
- Observabilidade: Micrometer + Micrometer Tracing (OpenTelemetry bridge) + Spring Boot Actuator
- Contêiner: **Dockerfile multi-estágio** por serviço
- Orquestração: manifestos **Kubernetes** (Deployment, Service, HorizontalPodAutoscaler)
- Simulação de parceiros externos: WireMock (ou um stub Spring Boot simples) para simular as APIs das transportadoras, incluindo um cenário de lentidão/falha

## Contexto de negócio

A RotaCerta oferece a lojas virtuais um serviço de entregas: consulta de preço e prazo de frete, registro do pedido, escolha de transportadora, roteirização e rastreamento. Processa em média 400 mil cotações de frete por hora, com picos dez vezes maiores em datas promocionais.

**O incidente que motiva o caso:** a API de uma transportadora parceira (TransVale) começou a responder em até 30 segundos (normal: 200 ms). Sem timeout configurado, isso esgotou as threads do serviço de Cotação, que por sua vez derrubou o serviço de Pedidos — uma falha em cascata que durou quase duas horas.

## Serviços a construir

1. **cotacao-frete-service** — calcula preço e prazo consultando (em tempo real) três "transportadoras parceiras" simuladas; é aqui que os padrões de resiliência devem ser aplicados
2. **pedidos-service** — registra pedidos de entrega; antes de confirmar, chama o `cotacao-frete-service` para validar o valor (com timeout configurado — ao contrário do incidente original)

> Roteirização, Rastreamento e Notificações (mencionados no caso como consumidores assíncronos de eventos) são **opcionais/stretch goal**.

## Padrões de resiliência obrigatórios (aplicados no `cotacao-frete-service`, ao chamar as transportadoras)

| Padrão | Configuração exigida |
|---|---|
| **Timeout** | 800 ms para chamadas a transportadoras parceiras; 1,2 s para chamadas internas entre serviços (ex. Pedidos → Cotação) |
| **Retry com backoff e jitter** | No máximo 2 tentativas, **apenas** para operações idempotentes (ex. GET de cotação); esperas crescentes (100 ms, 200 ms, 400 ms) acrescidas de valor aleatório |
| **Circuit Breaker** | Abre com **50% de falhas em 20 chamadas**; permanece aberto por **30 s**, depois entra em half-open |
| **Fallback** | Quando o circuito está aberto, retornar uma tabela de frete de referência em cache, marcando a cotação como `"estimada": true` — nunca uma tela de erro |
| **Bulkhead** | No máximo **40 chamadas simultâneas** por transportadora (pool de threads/conexões isolado por parceiro, para que a lentidão de uma não consuma os recursos de todas) |
| **Rate limiting** | 50 requisições/segundo por loja (pode ser aplicado no controller de entrada do `cotacao-frete-service`) |

**Cenário de teste obrigatório:** simular (via WireMock ou stub) uma transportadora que passa a responder em 30 s, e verificar que: (1) o circuito abre; (2) o fallback com a tabela de referência é usado; (3) o restante do sistema (outras transportadoras, serviço de Pedidos) continua respondendo dentro do orçamento de tempo.

## Health checks (lição do incidente — obrigatório)

- `readinessProbe`: verifica **apenas as dependências do próprio serviço** (ex. conexão com o próprio banco), nunca transportadoras parceiras externas
- `livenessProbe`: verifica **apenas se o processo está travado**, nunca dependências externas

(No incidente original, a verificação de saúde consultava as transportadoras: quando uma ficou lenta, as instâncias foram reiniciadas em sequência sem benefício algum. Essa correção deve ficar explícita no código/configuração.)

## Observabilidade obrigatória

- Instrumentar com Micrometer Tracing (compatível com OpenTelemetry): cada requisição gera um trace com spans para cada chamada relevante (ex. chamada à transportadora, consulta ao banco)
- Propagar o contexto de trace entre `pedidos-service` e `cotacao-frete-service` (header `traceparent`, padrão W3C)
- Correlacionar logs com o `trace_id` (ex. via MDC)
- Expor métricas dos **quatro sinais de ouro** (latência, tráfego, erros, saturação) via `/actuator/metrics` e/ou `/actuator/prometheus`
- Expor o estado dos circuit breakers via `/actuator/circuitbreakers` e `/actuator/circuitbreakerevents`

## Contêineres e orquestração

- Dockerfile multi-estágio por serviço: primeiro estágio compila (JDK), segundo estágio roda a aplicação com imagem mínima (JRE), **usuário sem privilégios de administrador**
- Configuração (endereços de transportadoras, timeouts) via **variáveis de ambiente**, nunca embutida na imagem
- Manifesto Kubernetes `Deployment` com `resources.requests`/`limits` de CPU e memória definidos, e as duas probes configuradas conforme a seção acima
- `HorizontalPodAutoscaler`: entre **4 e 40 réplicas**, alvo de **60% de uso médio de CPU**

## Endpoints mínimos

| Serviço | Verbo | Rota | Comportamento |
|---|---|---|---|
| cotacao-frete-service | GET | `/v1/cotacoes?origem=&destino=&peso=` | Consulta as 3 transportadoras (com resiliência aplicada) e devolve a melhor cotação, ou a estimada via fallback |
| pedidos-service | POST | `/v1/pedidos` | Valida a cotação junto ao `cotacao-frete-service` (com timeout de 1,2 s) e registra o pedido |

## Critérios de aceite

- [ ] Timeout, retry, circuit breaker, fallback e bulkhead configurados exatamente com os valores da tabela acima
- [ ] Teste automatizado que simula a transportadora lenta e comprova que o circuito abre e o fallback é usado
- [ ] `readinessProbe`/`livenessProbe` não dependem de serviços externos
- [ ] Trace distribuído de uma criação de pedido visível de ponta a ponta, incluindo a chamada à transportadora
- [ ] Dockerfile multi-estágio e manifestos Kubernetes (Deployment + HPA) incluídos no repositório

## Executar o projeto

Requisitos: Java 17+, Maven 3.9+ e Docker com Docker Compose.

```bash
mvn test
docker compose up --build
```

Serviços locais:

- Cotação: `GET http://localhost:8081/v1/cotacoes?origem=SP&destino=RJ&peso=2`, enviando `X-Loja-Id` para aplicar o limite por loja.
- Pedidos: `POST http://localhost:8082/v1/pedidos` com `{"lojaId":"loja-1","origem":"SP","destino":"RJ","peso":2}`.
- Health, métricas e circuit breakers: `/actuator/health`, `/actuator/prometheus` e `/actuator/circuitbreakers` no serviço de cotação.
- Visualização de traces distribuídos: `http://localhost:16686` (Jaeger iniciado pelo Compose).

Para reproduzir a lentidão da TransVale com o Compose, use `TRANSVALE_DELAY_MS=30000 docker compose up --build`. O timeout de 800 ms, retry idempotente e circuit breaker mantêm as demais transportadoras disponíveis; a cotação de referência é retornada com `"estimada": true`. O teste `SlowCarrierResilienceTest` simula essa resposta de 30 segundos e verifica o circuito, o fallback e as cotações das outras transportadoras.

Os endereços das transportadoras, o endpoint interno e os atrasos/falhas do stub são configuráveis por variáveis de ambiente. Os pedidos são mantidos em memória nesta implementação de referência. Os probes de readiness e liveness verificam exclusivamente os estados locais do processo, sem consultar parceiros.

Os manifestos de Kubernetes estão em `k8s/`; eles incluem Deployments, Services, probes e HPA de 4 a 40 réplicas com alvo de 60% de CPU. Configure `OTEL_EXPORTER_OTLP_ENDPOINT` para o collector do cluster. A propagação W3C `traceparent` entre os serviços é feita pelo cliente HTTP instrumentado do Spring Boot, e logs do Spring incluem os identificadores de trace/span no MDC.
