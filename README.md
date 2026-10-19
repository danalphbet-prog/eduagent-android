# EduAgent — MVP Android com Qwen local e SmolVLM

Aplicativo de chat **de texto e imagens** para Android, com inferência executada no próprio celular através do **llama.cpp/libmtmd**: **Qwen2.5-1.5B-Instruct Q4_K_M** para texto e **SmolVLM-500M-Instruct Q4_K_M** com projetor visual f16 para imagens. Primeiro experimento de um tutor educativo pessoal, sem monitoramento de outros apps, voz, câmeras ou acesso escolar nesta fase.

## O que já está implementado

- Interface clara com bolhas, profundidade, topo de status e campo de texto fixado acima da navegação/teclado por `WindowInsets` e `adjustResize`.
- Download assistido na primeira abertura, **uma vez**: ~1,12 GB. Arquivo GGUF mantido no armazenamento privado do aplicativo, com retomada de download interrompido e verificação SHA-256. Reabrir o app não faz download novamente enquanto o arquivo verificado permanecer instalado.
- Chat responsivo, com geração de texto gradual (streaming), histórico preservado em `chat_history_v1.json` no dispositivo e contexto por sessão, até 4.096 tokens.
- Pressão longa em uma bolha: detalhes da mensagem, tempo total, tempo até a primeira saída, prefill, tokens de entrada/saída e velocidade estimada. Os contadores são medidos pelo runtime, **não redigidos pelo modelo**.
- Interface `ChatRepository` separada para permitir futura integração ao Firebase, sem Firebase nesta versão.
- Botão **+** ao lado da digitação: seleciona foto da galeria/documentos do Android, copia para o armazenamento privado, permite visualizar e remover o anexo antes de enviar.
- Com uma imagem selecionada, escreva `@VLM O que tem na imagem?` e envie. O SmolVLM interpreta a imagem **localmente**, usando dois arquivos GGUF: pesos Q4 (~303 MB) **e** projetor f16 (~199 MB). O download é opcional e único, retoma de onde parou e verifica ambos por SHA-256. O modelo visual não é usado para conversas sem imagens.
- Ícone **☷** no topo abre um modal **Modelos locais** com estado dos modelos Qwen e SmolVLM, opção de download e remoção do modelo visual. Segurar uma mensagem visual mostra tokens e tempo.

**Requisitos:** aparelho **Android 13 (API 33) ou superior, 64 bits**, pelo menos ~2,1 GB livres para instalar os dois modelos e o projetor, e memória suficiente para modelos + contexto + Android. Para visão, recomendamos **8 GB de RAM ou mais** para teste inicial, porque Qwen e SmolVLM podem coexistir na memória durante a análise. O tempo de geração varia muito por SoC; não garantimos desempenho. Internet necessária somente para baixar modelos.

## Como gerar o APK

Abra a pasta no Android Studio com **JDK 17**, Android SDK API 36, NDK `29.0.13113456` e CMake `3.31.6` disponíveis. Primeiro baixe o código-fonte nativo fixado:

```bash
bash scripts/bootstrap-llama.sh
./gradlew :app:assembleDebug
```

O APK será produzido em `app/build/outputs/apk/debug/app-debug.apk`. Também existe `.github/workflows/android-debug.yml` para compilar e disponibilizar um APK no artefato da aba **Actions** em um repositório GitHub que contenha este projeto.

> O arquivo ZIP do projeto não contém o modelo GGUF nem os fontes grandes de llama.cpp; ambos são obtidos posteriormente, respectivamente pelo app no celular e pelo script de preparação/CI. Não é preciso instalar Debian, proot, Termux nem root no Android. O kernel Linux já faz parte do Android, e as métricas são coletadas pelo código nativo.

## Fluxo de uso

1. Abra o app e aceite o download inicial (recomendamos Wi-Fi). É possível cancelar e retomar na próxima abertura.
2. Aguarde a verificação SHA-256 e o carregamento local do modelo.
3. Escreva e envie uma mensagem. O tutor responde aos poucos.
4. Segure uma mensagem para abrir as estatísticas reais da inferência.
5. Feche e reabra. As mensagens e o modelo continuam salvos no armazenamento privado do app. Desinstalar o aplicativo pode apagá-los.
6. Para analisar imagem, toque em **+**, selecione uma foto e envie a mensagem **`@VLM O que tem na imagem?`**. Na primeira vez, aceite instalar o SmolVLM (pesos Q4 + projetor). Gerencie-o pelo ícone no topo. O analisador visual gera uma resposta completa, sem streaming nesta versão.

## Estrutura

| Arquivo | Responsabilidade |
| --- | --- |
| `app/.../MainActivity.kt` | Tela, teclado, download, envio, streaming, detalhes |
| `app/.../MessageAdapter.kt` | Bolhas, mensagens e métricas |
| `app/.../ModelRepository.kt` | Download, retomada, SHA-256 e cache persistente |
| `app/.../VisionModelRepository.kt` | Download Q4 e projetor, cache e remoção |
| `app/.../ImageAttachmentStore.kt` | Importação local segura dos anexos selecionados |
| `app/.../ChatRepository.kt` | Persistência local, ponto de extensão para Firebase |
| `lib/.../InferenceEngineImpl.kt` | Interface Kotlin ↔ JNI e thread nativa |
| `lib/src/main/cpp/ai_chat.cpp` | Inferência, contexto e contagem de tokens |
| `lib/src/main/cpp/vision.cpp` | Inferência visual local com libmtmd/SmolVLM e contagem de tokens |

## Limitações desta primeira versão

- O assistente não tem controle parental do sistema, não acompanha aplicativos/sites e não possui permissões de acessibilidade para isso. Esse recurso exigirá projeto separado, consentimento e proteções específicas para crianças.
- Não há vínculo de professor, criança e responsáveis; apenas um chat local, sem contas ou sincronização Firebase.
- Um modelo de 1,5B parâmetros pode errar, especialmente em conteúdo pedagógico ou assuntos sensíveis; o acompanhamento humano continua essencial.
- As estatísticas são de uma inferência local na sessão. `Tempo total` inclui agendamento e entrega à interface; `prefill` é a medição nativa da avaliação dos tokens de entrada. `Tokens/s` é uma estimativa baseada no tempo total menos prefill, não um benchmark isolado do hardware.
- O contexto é limitado a 4.096 tokens e reaproveita a sessão enquanto o app está aberto; ao reabrir, um resumo textual de até oito mensagens recentes é acrescentado ao prompt inicial. Não é uma memória ilimitada.
- A análise de imagem é feita para **uma imagem de cada vez**; o SmolVLM é pequeno e pode falhar em OCR de letra pequena, textos complexos, gráficos e raciocínio visual. Não monitora outros aplicativos ou a tela e não ativa câmera: somente imagens escolhidas manualmente.

## Licenças e origens

Camada nativa derivada do exemplo oficial [`ggml-org/llama.cpp/examples/llama.android`](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android), com uma revisão fixa para reprodução. `llama.cpp` é licenciado sob MIT; mantenha os respectivos avisos no uso/distribuição. Modelo de [`Qwen/Qwen2.5-1.5B-Instruct-GGUF`](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) (licença Apache-2.0 na ficha do modelo). Verifique os termos de ambos antes de distribuição pública.

Pesos e projetor visuais do repositório [`pierretokns/SmolVLM-500M-Instruct-GGUF`](https://huggingface.co/pierretokns/SmolVLM-500M-Instruct-GGUF). A base SmolVLM é distribuída pela Hugging Face; confira licenças de modelo e arquivos de conversão antes da publicação.
