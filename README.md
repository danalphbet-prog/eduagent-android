# EduAgent 0.3 — Qwen, SmolVLM e Memory Engine local

Aplicativo de chat **de texto, imagens e voz** para Android, com inferência executada no próprio celular através do **llama.cpp/libmtmd**: **Qwen2.5-1.5B-Instruct Q4_K_M** para texto e **SmolVLM-500M-Instruct Q4_K_M** com projetor visual f16 para imagens. A bolha opt-in reconhece perguntas pelo serviço de reconhecimento offline do Android e fala trechos conforme a resposta é gerada. Se o motor Android Kyutai Pocket TTS estiver instalado com voz pt-BR, ele tem preferência; caso contrário, o TTS padrão é utilizado.

## Novidades da versão 0.3

- **Memory Engine profunda** ativada por padrão, com **instruções ativas**, **lembrança de tarefas** e **resumo automático** também ativados. As opções ficam na engrenagem ⚙ da barra superior, e persistem entre reinicializações.
- Estado salvo em `conversation_memory_v1.json` (armazenamento privado); a conversa continua no arquivo original `chat_history_v1.json`, de forma a **preservar o histórico e os modelos já baixados** numa atualização compatível do APK.
- `ConversationBridge` inclui histórico textual recente nas perguntas do Qwen, em vez de transmitir somente o pedido atual; compartilha também descrições das imagens com o Qwen. SmolVLM recebe instruções e resumo. O motor extrai um **resumo compacto de texto anterior**, sem um segundo modelo LLM; não é memória ilimitada.
- **RAG local** habilitado por padrão: recupera por relevância lexical (ranking BM25) passagens de conversas antigas em `chat_history_v1.json` que saíram da janela recente. Inclui conversas com imagens via suas descrições textuais; não consulta rede nem carrega modelo de embeddings. Na engrenagem é possível desativar ou escolher **1 a 6 lembranças** recuperadas por pergunta (padrão: 4). O RAG não aumenta a capacidade física de contexto do Qwen (4.096 tokens); seleciona informações relevantes para ocupar melhor essa janela.
- **Checklists dirigidas por regras**: comandos de criação/edição e itens curtos têm resposta determinística, reduzindo os erros do pequeno Qwen. Teste: "Crie uma checklist" → "Janela." → "Coloque o okay no lugar do ponto" → "O emoji" → "Porta.". Resultado inclui `🆗 Janela` e `🆗 Porta`.
- Gravadores do chat e da bolha usam uma operação de **mesclagem com exclusão mútua**, evitando perda de novas mensagens de um componente pelo outro; mensagens concluídas não são rebaixadas a parciais por gravações atrasadas.
- **Botões `+`, bolha e enviar centralizados verticalmente** com relação à caixa de texto, também quando ela cresce para até quatro linhas.
- **Kyutai Pocket TTS**: preferência pelo [motor comunitário Android](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine) `org.pockettts.android.engine` quando instalado com modelo pt-BR; fallback explícito ao TTS Android. O runtime e os pesos Kyutai **não são empacotados neste ZIP/APK**, portanto a voz Pocket exige instalação e configuração separadas. O reconhecimento de fala continua a depender do serviço offline do Android.
- Testes de unidade `MemoryEngineTest.kt` (checklist, instruções, resumo, ponte de contexto e RAG) e testes instrumentados `MemoryRepositoryInstrumentedTest.kt` (persistência e concorrência de gravações). A CI executa testes de unidade antes do APK.

## O que já está implementado

- Interface clara com bolhas, profundidade, topo de status e campo de texto fixado acima da navegação/teclado por `WindowInsets` e `adjustResize`.
- Download assistido na primeira abertura, **uma vez**: ~1,12 GB. Arquivo GGUF mantido no armazenamento privado do aplicativo, com retomada de download interrompido e verificação SHA-256. Reabrir o app não faz download novamente enquanto o arquivo verificado permanecer instalado.
- Chat responsivo, com geração de texto gradual (streaming), histórico e memória preservados no dispositivo e contexto por sessão, até 4.096 tokens.
- Pressão longa em uma bolha: detalhes da mensagem, tempo total, tempo até a primeira saída, prefill, tokens de entrada/saída e velocidade estimada. Os contadores são medidos pelo runtime, **não redigidos pelo modelo**.
- No mesmo modal aberto ao segurar uma mensagem, toque em **Copiar texto** para enviar o conteúdo ao clipboard do Android.
- Interface `ChatRepository` separada para permitir futura integração ao Firebase, sem Firebase nesta versão.
- Botão **+** ao lado da digitação: seleciona foto da galeria/documentos do Android, copia para o armazenamento privado, permite visualizar e remover o anexo antes de enviar.
- Com uma imagem selecionada, escreva `@VLM O que tem na imagem?` e envie. O SmolVLM interpreta a imagem **localmente**, usando dois arquivos GGUF: pesos Q4 (~303 MB) **e** projetor f16 (~199 MB). O download é opcional e único, retoma de onde parou e verifica ambos por SHA-256. O modelo visual não é usado para conversas sem imagens.
- Ícone **☷** no topo abre um modal **Modelos locais** com estado dos modelos Qwen e SmolVLM, opção de download e remoção do modelo visual. Segurar uma mensagem visual mostra tokens e tempo.
- Ícone **⚙** abre configurações persistentes: limite de geração Qwen (32–512 tokens), limite SmolVLM (16–256 tokens), criatividade/temperatura (0–1), quantidade de mensagens recentes no contexto (2–16), respostas objetivas, compartilhamento de contexto entre as duas IAs e reutilização da última foto. Também oferece perfil de memória leve/equilibrada/profunda, instruções ativas, tarefas, resumo automático, RAG e profundidade de recuperação.
- **Contexto compartilhado:** depois que `@VLM` descreve uma foto, o Qwen passa a receber essa descrição como texto em perguntas seguintes, como "pode traduzir?" ou "explique melhor a imagem". O SmolVLM também recebe trechos limitados do chat textual. O Qwen **não recebe pixels**: sua compreensão visual fica restrita ao que o SmolVLM descreveu.
- Botão **◉** ao lado de enviar: solicita permissões separadas de sobreposição e microfone, inicia um serviço de primeiro plano e mostra uma bolha de 56dp arrastável sobre outros apps. A bolha começa em **hibernação**, com microfone fechado. O toque reproduz uma saudação e inicia uma janela de **10 segundos de escuta**, indicada por contagem regressiva. Novo toque cancela e volta à hibernação. Não há ativação por ruídos altos. A notificação permanente possui **Desativar**. O app não armazena amostras de áudio.
- **Voz atual:** a fala reconhecida entra no histórico do chat, é respondida pelo mesmo Qwen e a resposta é narrada em pedaços por frase, sem aguardar os 2 mil caracteres completos. Usa reconhecimento de fala local do Android. A saída de voz dá preferência ao **Kyutai Pocket TTS Android Engine** comunitário, *se instalado e configurado com um modelo pt-BR*; sem ele, usa o TTS do sistema. O Pocket **não vem dentro do APK** e depende de instalação externa. O áudio gravado não é salvo nem enviado pelo EduAgent, mas as transcrições e respostas são armazenadas localmente.

**Requisitos:** aparelho **Android 13 (API 33) ou superior, 64 bits**, pelo menos ~2,1 GB livres para instalar os dois modelos e o projetor, e memória suficiente para modelos + contexto + Android. Para visão, recomendamos **8 GB de RAM ou mais** para teste inicial, porque Qwen e SmolVLM podem coexistir na memória durante a análise. O tempo de geração varia muito por SoC; não garantimos desempenho. Internet necessária somente para baixar modelos.

## Como gerar o APK

Abra a pasta no Android Studio com **JDK 17**, Android SDK API 36, NDK `29.0.13113456` e CMake `3.31.6` disponíveis. Primeiro baixe o código-fonte nativo fixado:

```bash
bash scripts/bootstrap-llama.sh
./gradlew :app:assembleDebug
```

O APK será produzido em `app/build/outputs/apk/debug/app-debug.apk`. Para executar os testes JVM, use `./gradlew :app:testDebugUnitTest`. Também existe `.github/workflows/android-debug.yml` para rodar esses testes, compilar e disponibilizar um APK no artefato da aba **Actions** em um repositório GitHub que contenha este projeto. Os testes instrumentados de persistência precisam de dispositivo/emulador e executam por `./gradlew :app:connectedDebugAndroidTest`.

> O arquivo ZIP do projeto não contém o modelo GGUF nem os fontes grandes de llama.cpp; ambos são obtidos posteriormente, respectivamente pelo app no celular e pelo script de preparação/CI. Não é preciso instalar Debian, proot, Termux nem root no Android. O kernel Linux já faz parte do Android, e as métricas são coletadas pelo código nativo.

## Fluxo de uso

1. Abra o app e aceite o download inicial (recomendamos Wi-Fi). É possível cancelar e retomar na próxima abertura.
2. Aguarde a verificação SHA-256 e o carregamento local do modelo.
3. Escreva e envie uma mensagem. O tutor responde aos poucos.
4. Segure uma mensagem para abrir as estatísticas reais da inferência e **copiar** seu texto.
5. Feche e reabra. As mensagens e o modelo continuam salvos no armazenamento privado do app. Desinstalar o aplicativo pode apagá-los.
6. Para analisar imagem, toque em **+**, selecione uma foto e envie a mensagem **`@VLM O que tem na imagem?`**. Na primeira vez, aceite instalar o SmolVLM (pesos Q4 + projetor). Gerencie-o pelo ícone no topo. O analisador visual gera uma resposta completa, sem streaming nesta versão.
7. Depois de receber uma descrição, pergunte ao Qwen **"Pode traduzir a descrição da foto?"** para continuar com o contexto visual. Para analisar a mesma imagem de novo sem anexar, use `@VLM` (se a opção de reutilização estiver ligada). Use **⚙** para reduzir tokens quando desejar respostas rápidas.
8. Para o modo flutuante, toque **◉**, leia o aviso e permita **Sobrepor outros aplicativos** e **Microfone**. Arraste a bolha, toque nela para ouvir a saudação, fale sua pergunta durante a janela de 10 segundos, e aguarde a resposta falada por trechos. Para cancelar antes, toque novamente. Sem toque, o microfone fica fechado. O serviço offline de reconhecimento deve estar disponível e configurado no celular.

## Estrutura

| Arquivo | Responsabilidade |
| --- | --- |
| `app/.../MainActivity.kt` | Tela, teclado, download, envio, streaming, detalhes |
| `app/.../MessageAdapter.kt` | Bolhas, mensagens e métricas |
| `app/.../ModelRepository.kt` | Download, retomada, SHA-256 e cache persistente |
| `app/.../VisionModelRepository.kt` | Download Q4 e projetor, cache e remoção |
| `app/.../ImageAttachmentStore.kt` | Importação local segura dos anexos selecionados |
| `app/.../ChatRepository.kt` | Persistência local, ponto de extensão para Firebase |
| `app/.../AiSettings.kt` | Preferências locais de desempenho e memória |
| `app/.../ConversationBridge.kt` | Transferência de contexto textual entre Qwen e SmolVLM |
| `app/.../MemoryEngine.kt` | Regras determinísticas, instruções ativas, checklist e resumo incremental |
| `app/.../MemoryRepository.kt` | Persistência atômica da memória e migração inicial do histórico 0.2 |
| `app/.../RagRetriever.kt` | Busca BM25 nos turnos antigos já persistidos e seleção de passagens relevantes |
| `app/.../PocketBubbleService.kt` | Sobreposição arrastável, hibernação/saudação/escuta, reconhecimento de fala local, integração Qwen e narração por trechos |
| `lib/.../InferenceEngineImpl.kt` | Interface Kotlin ↔ JNI e thread nativa |
| `lib/src/main/cpp/ai_chat.cpp` | Inferência, contexto e contagem de tokens |
| `lib/src/main/cpp/vision.cpp` | Inferência visual local com libmtmd/SmolVLM e contagem de tokens |

## Limitações desta primeira versão

- O assistente não tem controle parental do sistema, não acompanha aplicativos/sites e não possui permissões de acessibilidade para isso. Esse recurso exigirá projeto separado, consentimento e proteções específicas para crianças.
- Não há vínculo de professor, criança e responsáveis; apenas um chat local, sem contas ou sincronização Firebase.
- Um modelo de 1,5B parâmetros pode errar, especialmente em conteúdo pedagógico ou assuntos sensíveis; o acompanhamento humano continua essencial.
- As estatísticas são de uma inferência local na sessão. `Tempo total` inclui agendamento e entrega à interface; `prefill` é a medição nativa da avaliação dos tokens de entrada. `Tokens/s` é uma estimativa baseada no tempo total menos prefill, não um benchmark isolado do hardware.
- O contexto do Qwen continua limitado a 4.096 tokens. O Memory Engine reinsere informações compactadas e tarefas ativas a cada pergunta, e o RAG encontra trechos antigos por correspondência de palavras. O resumo é **extrativo** e o RAG lexical pode não reconhecer sinônimos ou relações sem termos em comum. Não é memória ilimitada nem garante fidelidade perfeita.
- O limite de **tokens de resposta** é diferente da janela de contexto. Reduzi-lo geralmente acelera a geração, mas não elimina o tempo de leitura/prefill de imagens nem a carga dos modelos. O compartilhamento entre IAs utiliza resumos textuais, não memória multimodal conjunta.
- A análise de imagem é feita para **uma imagem de cada vez**; o SmolVLM é pequeno e pode falhar em OCR de letra pequena, textos complexos, gráficos e raciocínio visual. Não monitora outros aplicativos ou a tela e não ativa câmera: somente imagens escolhidas manualmente.
- A voz depende de haver **reconhecimento de fala no dispositivo** e **um motor TTS português** instalados/configurados no Android. Caso o reconhecimento offline não esteja disponível, a bolha informa a limitação e hiberna; não transfere automaticamente o áudio para a nuvem. O assistente **não reage a ruídos** enquanto hiberna. Android pode suspender serviços de primeiro plano; não reinicia após forçar parada ou reiniciar o telefone. Para a voz Kyutai funcionar, é necessário instalar o motor comunitário e um pacote de voz pt-BR; do contrário, a bolha usa o sintetizador Android.
- **Atualização do APK:** o código marca esta revisão como **v0.3.0 (versionCode 3)**. Não desinstale uma versão antiga antes de proteger o modelo e histórico; APKs debug de execuções CI diferentes podem ter chaves de assinatura distintas e não atualizar uns aos outros.

## Licenças e origens

Camada nativa derivada do exemplo oficial [`ggml-org/llama.cpp/examples/llama.android`](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android), com uma revisão fixa para reprodução. `llama.cpp` é licenciado sob MIT; mantenha os respectivos avisos no uso/distribuição. Modelo de [`Qwen/Qwen2.5-1.5B-Instruct-GGUF`](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) (licença Apache-2.0 na ficha do modelo). Verifique os termos de ambos antes de distribuição pública.

Pesos e projetor visuais do repositório [`pierretokns/SmolVLM-500M-Instruct-GGUF`](https://huggingface.co/pierretokns/SmolVLM-500M-Instruct-GGUF). A base SmolVLM é distribuída pela Hugging Face; confira licenças de modelo e arquivos de conversão antes da publicação.
