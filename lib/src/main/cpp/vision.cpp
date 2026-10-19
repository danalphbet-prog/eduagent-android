#include <jni.h>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
int64_t vision_metrics[3] = {0, 0, 0};

void ensure(bool condition, const char * message) {
    if (!condition) throw std::runtime_error(message);
}

std::string utf8(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    ensure(chars != nullptr, "Falha ao ler os parâmetros da imagem");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

using clock_type = std::chrono::steady_clock;
int64_t elapsed_ms(clock_type::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(clock_type::now() - start).count();
}

std::string run_vision(const std::string & weights,
                       const std::string & projector,
                       const std::string & image,
                       const std::string & question) {
    vision_metrics[0] = vision_metrics[1] = vision_metrics[2] = 0;

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(weights.c_str(), model_params), &llama_model_free);
    ensure(model != nullptr, "Não foi possível carregar o SmolVLM");

    llama_context_params params = llama_context_default_params();
    params.n_ctx = 4096;
    params.n_batch = 512;
    params.n_ubatch = 512;
    params.n_threads = 4;
    params.n_threads_batch = 4;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
        llama_init_from_model(model.get(), params), &llama_free);
    ensure(ctx != nullptr, "Sem memória para inicializar o SmolVLM");

    mtmd_context_params vision_params = mtmd_context_params_default();
    vision_params.use_gpu = false;
    vision_params.n_threads = 4;
    vision_params.image_max_tokens = 512;
    vision_params.image_min_tokens = 64;
    std::unique_ptr<mtmd_context, decltype(&mtmd_free)> vision(
        mtmd_init_from_file(projector.c_str(), model.get(), vision_params), &mtmd_free);
    ensure(vision != nullptr && mtmd_support_vision(vision.get()),
           "Projetor incompatível ou não foi possível carregar a visão");

    auto bitmap_wrapper = mtmd_helper_bitmap_init_from_file(
        vision.get(), image.c_str(), false, mtmd_helper_init_opt_default());
    std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(
        bitmap_wrapper.bitmap, &mtmd_bitmap_free);
    ensure(bitmap != nullptr, "A imagem não pôde ser aberta pelo motor visual");

    common_chat_templates_ptr templates = common_chat_templates_init(model.get(), "");
    ensure(templates != nullptr, "Template de conversa indisponível");
    common_chat_msg request;
    request.role = "user";
    request.content = std::string(mtmd_default_marker()) + "\n" + question;
    const std::vector<common_chat_msg> history;
    const std::string formatted = common_chat_format_single(
        templates.get(), history, request, true, false);

    mtmd_input_text input = {formatted.data(), formatted.size(), true, true};
    const mtmd_bitmap * bitmaps[] = {bitmap.get()};
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(
        mtmd_input_chunks_init(), &mtmd_input_chunks_free);
    ensure(chunks != nullptr, "Falha ao preparar a imagem");
    ensure(mtmd_tokenize(vision.get(), chunks.get(), &input, bitmaps, 1) == 0,
           "Falha ao converter imagem em tokens visuais");

    const auto prefill_started = clock_type::now();
    llama_pos position = 0;
    ensure(mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(),
        0, 0, 512, true, &position) == 0, "Falha ao processar imagem e instrução");
    vision_metrics[0] = static_cast<int64_t>(mtmd_helper_get_n_tokens(chunks.get()));
    vision_metrics[2] = elapsed_ms(prefill_started);

    common_params_sampling sampler_params;
    sampler_params.temp = 0.3f;
    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
        common_sampler_init(model.get(), sampler_params), &common_sampler_free);
    ensure(sampler != nullptr, "Não foi possível preparar geração visual");

    common_batch batch(ctx.get());
    const auto * vocab = llama_model_get_vocab(model.get());
    std::string response;
    for (int i = 0; i < 192 && position + 1 < static_cast<llama_pos>(llama_n_ctx(ctx.get())); ++i) {
        const llama_token token = common_sampler_sample(sampler.get(), ctx.get(), -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) break;
        response += common_token_to_piece(ctx.get(), token);
        vision_metrics[1]++;
        batch.clear();
        batch.add(token, position++, 0, true);
        ensure(llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) == 0,
               "Erro nativo ao gerar resposta visual");
    }
    return response.empty() ? "Não consegui interpretar esta imagem." : response;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_analyzeImageNative(
    JNIEnv * env, jobject, jstring jweights, jstring jprojector,
    jstring jimage, jstring jquestion) {
    try {
        std::string result = run_vision(utf8(env, jweights), utf8(env, jprojector),
                                        utf8(env, jimage), utf8(env, jquestion));
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception & e) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception) env->ThrowNew(exception, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeLastVisionMetrics(JNIEnv * env, jobject) {
    jlongArray array = env->NewLongArray(3);
    const jlong stats[] = {static_cast<jlong>(vision_metrics[0]),
                           static_cast<jlong>(vision_metrics[1]),
                           static_cast<jlong>(vision_metrics[2])};
    if (array) env->SetLongArrayRegion(array, 0, 3, stats);
    return array;
}
