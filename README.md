# EduAgent 0.6 — Qwen 3B, ícones Lucide offline, workspace de código e Pocket TTS

Aplicativo de chat **de texto, imagens, código e voz** para Android, com inferência executada no próprio celular através do **llama.cpp/libmtmd**: **Qwen2.5-3B-Instruct Q4_K_M** para texto e **SmolVLM-500M-Instruct Q4_K_M** com projetor visual f16 para imagens. A bolha opt-in reconhece perguntas pelo serviço de reconhecimento offline do Android e fala trechos conforme a resposta é gerada. **O runtime nativo Kyutai Pocket TTS continua compilado e empacotado dentro do mesmo APK do EduAgent.** Depois do download opcional do modelo português, a bolha usa Pocket; sem os pesos, usa o TTS padrão.

## Novidades 0.6 — ícones, código e ferramentas locais

- **1.903 ícones Lucide SVG armazenados offline** em `app/src/main/assets/lucide/`, fornecidos pelo ZIP oficial enviado pelo usuário. Ícones usados no chat são convertidos para `VectorDrawable` nativo em `res/drawable`: engrenagem, enviar, anexo, microfone, cérebro/modelos, download, fechar, brilhar, copiar, abrir, terminal, pasta etc. O script de importação é `scripts/import_lucide.py` e a licença está em `licenses/LUCIDE_LICENSE`.
- **Blocos de código Markdown:** código cercado por três crases aparece com fonte monoespaçada, linguagem, **Copiar**, **Abrir**, **Download** (na pasta `Downloads/EduAgent`) e **Salvar arquivo**. Blocos `bash`/`sh` também oferecem **Executar** após confirmação do usuário.
- **`setData` revisável:** um bloco Markdown da linguagem `setData` contendo `{"path":"pasta/arquivo.ext","content":"texto do arquivo"}` oferece o botão **Aplicar setData**. O nome e os dados são revisados antes da escrita no diretório privado `files/code_workspace`; a IA não escreve automaticamente. Há limite de tamanho, validação contra `../` e caminhos absolutos.
- **Terminal local dentro do app:** disponível na engrenagem. Executa `/system/bin/sh` sob o usuário comum do EduAgent, **sem root e sem instalar Termux**, após confirmar o comando. Mostra código de saída, texto de erros e opção **Analisar com IA** (preenche uma mensagem para o usuário revisar e enviar). O tempo de execução é limitado a 12 segundos. **Não é um sandbox de segurança completo:** um comando autorizado pode alcançar arquivos privados acessíveis pelo aplicativo; confirme somente comandos confiáveis.
- **Contexto dos arquivos:** ao escrever **`@workspace`** na pergunta, a IA recebe a lista e alguns trechos curtos dos arquivos salvos no workspace. O app não envia automaticamente arquivos a cada mensagem. A IA pode propor novas versões via `setData`, sempre aprovadas manualmente.
- **Qwen2.5 3B Q4_K_M** no lugar do modelo padrão de 1.5B, obtido sob demanda na primeira abertura (~2,1 GB). O arquivo anterior tem nome diferente e é mantido na atualização. SmolVLM e Pocket TTS continuam separados. O modelo 3B utiliza mais RAM e pode ficar lento ou falhar em aparelhos com pouca memória.

## Voz progressiva com Kyutai Pocket TTS (novidade 0.5)

O APK do EduAgent **inclui o motor Pocket TTS** como módulo interno compilado a partir do porte comunitário MIT. Para não inflar o instalador, **os pesos do modelo português são baixados e verificados no primeiro uso**:

1. No EduAgent, abra **⚙ Configurações → Baixar / configurar voz Kyutai Pocket TTS**.
2. Toque em **Baixar voz em português (~198 MB)**. O aplicativo transfere o pacote FP32 v0.5.3, confere seu **SHA-256** e importa no armazenamento privado. Não é necessário outro aplicativo ou APK.
3. Toque em **Testar Pocket TTS agora** para ouvir a frase. Em **Abrir modelos e selecionar voz**, escolha outra voz do pacote, se desejar.
4. Ative **Falar respostas enquanto o Qwen escreve (Pocket TTS)** nas configurações e salve. Cada frase concluída, ou trecho longo sem pontuação, entra na fila de fala **antes de a resposta completa terminar**.

O idioma do pacote pode ter sotaque português europeu. A velocidade depende do aparelho e a primeira síntese pode levar mais tempo, pois os pesos precisam ser carregados; dividir a resposta em frases não garante reprodução instantânea. O chat não substitui silenciosamente o Pocket TTS pelo Android quando o usuário habilita esse modo. A bolha possui fallback explícito para o TTS padrão quando os pesos não estiverem disponíveis.

Fontes: [porte comunitário para Android](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine) e [Kyutai Pocket TTS original](https://github.com/kyutai-labs/pocket-tts). Código do motor com licença MIT; os pesos Pocket TTS são publicados sob CC BY 4.0. A atribuição e os termos devem ser mantidos ao redistribuir.

## Novidades da versão 0.3

- **Memory Engine profunda** ativada por padrão, com **instruções ativas**, **lembrança de tarefas** e **resumo automático** também ativados. As opções ficam na engrenagem ⚙ da barra superior, e persistem entre reinicializações.
- Estado salvo em `conversation_memory_v1.json` (armazenamento privado); a conversa continua no arquivo original `chat_history_v1.json`, de forma a **preservar o histórico e os modelos já baixados** numa atualização compatível do APK.
- `ConversationBridge` inclui histórico textual recente nas perguntas do Qwen, em vez de transmitir somente o pedido atual; compartilha também descrições das imagens com o Qwen. SmolVLM recebe instruções e resumo. O motor extrai um **resumo compacto de texto anterior**, sem um segundo modelo LLM; não é memória ilimitada.
- **RAG local** habilitado por padrão: recupera por relevância lexical (ranking BM25) passagens de conversas antigas em `chat_history_v1.json` que saíram da janela recente. Inclui conversas com imagens via suas descrições textuais; não consulta rede nem carrega modelo de embeddings. Na engrenagem é possível desativar ou escolher **1 a 6 lembranças** recuperadas por pergunta (padrão: 4). O RAG não aumenta a capacidade física de contexto do Qwen (4.096 tokens); seleciona informações relevantes para ocupar melhor essa janela.
- **Checklists dirigidas por regras**: comandos de criação/edição e itens curtos têm resposta determinística, reduzindo os erros do pequeno Qwen. Teste: "Crie uma checklist" → "Janela." → "Coloque o okay no lugar do ponto" → "O emoji" → "Porta.". Resultado inclui `🆗 Janela` e `🆗 Porta`.
- Gravadores do chat e da bolha usam uma operação de **mesclagem com exclusão mútua**, evitando perda de novas mensagens de um componente pelo outro; mensagens concluídas não são rebaixadas a parciais por gravações atrasadas.
- **Botões `+`, bolha e enviar centralizados verticalmente** com relação à caixa de texto, inclusive quando ela cresce para até quatro linhas. Na bolha flutuante, a estrela é desenhada geometricamente no centro.
- **Kyutai Pocket TTS**: motor nativo incorporado como módulo no mesmo APK; pesos de voz portugueses instalados sob demanda, sem outro aplicativo. O reconhecimento de fala continua a depender do serviço offline do Android.
- Testes de unidade `MemoryEngineTest.kt` (checklist, instruções, resumo, ponte de contexto e RAG) e testes instrumentados `MemoryRepositoryInstrumentedTest.kt` (persistência e concorrência de gravações). A CI executa testes de unidade antes do APK.

## O que já está implementado

- Interface clara com bolhas, profundidade, topo de status e campo de texto fixado acima da navegação/teclado por `WindowInsets` e `adjustResize`.
- Download assistido na primeira abertura da versão 0.6, **uma vez**: ~2,1 GB para Qwen 3B. Arquivo GGUF mantido no armazenamento privado do aplicativo, com retomada de download interrompido e verificação SHA-256. Reabrir o app não faz download novamente enquanto o arquivo verificado permanecer instalado.
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
- **Voz atual:** a fala reconhecida entra no histórico do chat, é respondida pelo mesmo Qwen e a resposta é narrada em pedaços por frase, sem aguardar a resposta inteira. Usa reconhecimento de fala local do Android. A saída dá preferência ao **Kyutai Pocket TTS embutido no EduAgent** quando o modelo português estiver baixado; sem ele, a bolha usa o TTS do sistema. O áudio gravado não é salvo nem enviado pelo EduAgent, mas as transcrições e respostas são armazenadas localmente.

**Requisitos:** aparelho **Android 13 (API 33) ou superior, 64 bits**. Libere pelo menos **2,5 GB só para baixar Qwen 3B**; SmolVLM ocupa mais ~502 MB, Pocket TTS ~198 MB, e a versão antiga de Qwen (~1,1 GB) continua armazenada se já instalada. Para testes recomendamos **8 GB de RAM ou mais**, especialmente com visão ativa. O tempo de geração varia muito por SoC; não garantimos desempenho. Internet necessária para baixar modelos (e para compilar dependências).

## Como gerar o APK

Abra a pasta no Android Studio com **JDK 17**, Android SDK API 36, NDK `29.0.13113456` e CMake `3.31.6` disponíveis. Primeiro baixe o código-fonte nativo fixado:

```bash
bash scripts/bootstrap-llama.sh
bash scripts/prepare_pocket_native_deps.sh
./gradlew :app:assembleDebug
```

O APK será produzido em `app/build/outputs/apk/debug/app-debug.apk`. Para executar os testes JVM, use `./gradlew :app:testDebugUnitTest`. Também existe `.github/workflows/android-debug.yml` para rodar esses testes, compilar e disponibilizar um APK no artefato da aba **Actions** em um repositório GitHub que contenha este projeto. Os testes instrumentados de persistência precisam de dispositivo/emulador e executam por `./gradlew :app:connectedDebugAndroidTest`.

> O projeto não contém os pesos GGUF de LLM/VLM nem os pesos de voz Pocket. O Qwen/SmolVLM/Pocket são obtidos pelo app conforme necessário, enquanto bibliotecas e dependências nativas são compiladas no GitHub Actions. Não é preciso instalar Debian, proot, Termux nem root no Android para usar o APK.

## Fluxo de uso

1. Abra o app e aceite o download inicial (recomendamos Wi-Fi). É possível cancelar e retomar na próxima abertura.
2. Aguarde a verificação SHA-256 e o carregamento local do modelo.
3. Escreva e envie uma mensagem. O tutor responde aos poucos.
4. Segure uma mensagem para abrir as estatísticas reais da inferência e **copiar** seu texto.
5. Feche e reabra. As mensagens e o modelo continuam salvos no armazenamento privado do app. Desinstalar o aplicativo pode apagá-los.
6. Para analisar imagem, toque em **+**, selecione uma foto e envie a mensagem **`@VLM O que tem na imagem?`**. Na primeira vez, aceite instalar o SmolVLM (pesos Q4 + projetor). Gerencie-o pelo ícone no topo. O analisador visual gera uma resposta completa, sem streaming nesta versão.
7. Depois de receber uma descrição, pergunte ao Qwen **"Pode traduzir a descrição da foto?"** para continuar com o contexto visual. Para analisar a mesma imagem de novo sem anexar, use `@VLM` (se a opção de reutilização estiver ligada). Use **⚙** para reduzir tokens quando desejar respostas rápidas.
8. Para o modo flutuante, toque **◉**, leia o aviso e permita **Sobrepor outros aplicativos** e **Microfone**. Arraste a bolha, toque nela para ouvir a saudação, fale sua pergunta durante a janela de 10 segundos, e aguarde a resposta falada por trechos. Para cancelar antes, toque novamente. Sem toque, o microfone fica fechado. O serviço offline de reconhecimento deve estar disponível e configurado no celular.
9. Peça "Crie um exemplo de código HTML" para testar os botões **Copiar**, **Abrir**, **Download** e **Salvar arquivo**. Abra **Configurações → Arquivos e projetos de código** para rever os arquivos salvos e pergunte "Revise meu projeto @workspace" para incluir trechos deles no contexto.
10. Para testar o terminal, abra **Configurações → Terminal Linux do aplicativo**, escreva `pwd` ou `ls`, revise a confirmação e veja a saída. O comando roda como o próprio aplicativo Android; não possui os programas de uma distribuição Linux completa.

## Estrutura

| Arquivo | Responsabilidade |
| --- | --- |
| `app/.../MainActivity.kt` | Tela, teclado, download, envio, streaming, detalhes |
| `app/.../MessageAdapter.kt` | Bolhas, mensagens e métricas |
| `app/.../CodeBlocks.kt` | Extração de blocos Markdown e identificação de ações de código |
| `app/.../WorkspaceTools.kt` | Escrita de arquivos validada, download, contexto opt-in e shell aprovado |
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
- **Comandos e arquivos propostos pela IA não são confiáveis.** `setData` exige aprovação, e shell também exige confirmação antes de rodar. O Android isola o aplicativo de outros apps, mas comandos autorizados podem acessar os dados privados do EduAgent. Não execute comandos destrutivos ou que baixem programas desconhecidos. A prévia HTML desativa JavaScript, acesso a arquivos e carregamentos da rede.
- Não há vínculo de professor, criança e responsáveis; apenas um chat local, sem contas ou sincronização Firebase.
- Um modelo de 3B parâmetros ainda pode errar ou produzir sintaxe inválida, especialmente em código, conteúdo pedagógico ou assuntos sensíveis; o acompanhamento humano continua essencial.
- As estatísticas são de uma inferência local na sessão. `Tempo total` inclui agendamento e entrega à interface; `prefill` é a medição nativa da avaliação dos tokens de entrada. `Tokens/s` é uma estimativa baseada no tempo total menos prefill, não um benchmark isolado do hardware.
- O contexto do Qwen continua limitado a 4.096 tokens. O Memory Engine reinsere informações compactadas e tarefas ativas a cada pergunta, e o RAG encontra trechos antigos por correspondência de palavras. O resumo é **extrativo** e o RAG lexical pode não reconhecer sinônimos ou relações sem termos em comum. Não é memória ilimitada nem garante fidelidade perfeita.
- O limite de **tokens de resposta** é diferente da janela de contexto. Reduzi-lo geralmente acelera a geração, mas não elimina o tempo de leitura/prefill de imagens nem a carga dos modelos. O compartilhamento entre IAs utiliza resumos textuais, não memória multimodal conjunta.
- A análise de imagem é feita para **uma imagem de cada vez**; o SmolVLM é pequeno e pode falhar em OCR de letra pequena, textos complexos, gráficos e raciocínio visual. Não monitora outros aplicativos ou a tela e não ativa câmera: somente imagens escolhidas manualmente.
- A voz depende de haver **reconhecimento de fala offline** disponível no dispositivo e **pesos de voz portugueses** baixados para usar Pocket. Caso o reconhecimento offline não esteja disponível, a bolha informa a limitação e hiberna; não transfere automaticamente o áudio para a nuvem. O assistente **não reage a ruídos** enquanto hiberna. Android pode suspender serviços de primeiro plano; não reinicia após forçar parada ou reiniciar o telefone. Sem o modelo Kyutai importado, a bolha usa o sintetizador Android.
- **Atualização do APK:** o código marca esta revisão como **v0.6.0 (versionCode 6)**. Não desinstale uma versão antiga antes de proteger o modelo e histórico; APKs debug de execuções CI diferentes podem ter chaves de assinatura distintas e não atualizar uns aos outros.

## Licenças e origens

Camada nativa derivada do exemplo oficial [`ggml-org/llama.cpp/examples/llama.android`](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android), com uma revisão fixa para reprodução. `llama.cpp` é licenciado sob MIT; mantenha os respectivos avisos no uso/distribuição. Modelo atualizado de [`Qwen/Qwen2.5-3B-Instruct-GGUF`](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF), cuja ficha de modelo indica a licença **qwen-research** (não assuma licença Apache-2.0 para esse tamanho). Verifique as condições de uso e redistribuição. Modelo antigo de 1.5B permanece no disco se instalado.

Ícones e biblioteca de arte vetorial do projeto [`lucide-icons/lucide`](https://github.com/lucide-icons/lucide). O ZIP fornecido pelo usuário foi importado offline; leia os avisos ISC/MIT preservados em `licenses/LUCIDE_LICENSE`. Não é preciso buscar os ícones na rede durante o uso do app.

Pesos e projetor visuais do repositório [`pierretokns/SmolVLM-500M-Instruct-GGUF`](https://huggingface.co/pierretokns/SmolVLM-500M-Instruct-GGUF). A base SmolVLM é distribuída pela Hugging Face; confira licenças de modelo e arquivos de conversão antes da publicação.

Pocket TTS: runtime adaptado do porte comunitário [`The-unknown-Shadowman/PocketTTS-Android-Engine`](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine), com licença MIT incluída em `licenses/POCKETTTS_ANDROID_ENGINE_LICENSE`. Arquivos nativos originais preservam avisos do projeto [`PocketTTS.cpp`](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/tree/main/vendor/PocketTTS.cpp). Modelo e vozes Kyutai TTS distribuídos sob **Creative Commons Attribution 4.0 (CC BY 4.0)**, não embutidos no APK e obtidos via download a pedido do usuário. Não faça clonagem de voz de terceiros sem autorização.
