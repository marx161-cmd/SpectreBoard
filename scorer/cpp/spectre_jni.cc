// JNI entry points for SpectreBoard's KenLM scorer.
//
// Replaces the stdin/stdout subprocess with in-process JNI calls.
// scoreAllNative() builds the context state once and scores all candidates in
// a single call — no IPC round-trips per candidate.

#include <jni.h>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "lm/model.hh"

static std::unique_ptr<lm::ngram::TrieModel> g_model;
static const lm::ngram::TrieModel::Vocabulary* g_vocab = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_termux_spectreboard_spectre_KenLmScorer_initNative(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    bool ok = false;
    try {
        lm::ngram::Config cfg;
        cfg.messages = nullptr;
        g_model = std::make_unique<lm::ngram::TrieModel>(path, cfg);
        g_vocab  = &g_model->GetVocabulary();
        ok = true;
    } catch (...) {
        g_model.reset();
        g_vocab = nullptr;
    }
    env->ReleaseStringUTFChars(modelPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Score every candidate against the same context in one call.
// contextStr: space-separated history words (oldest first, no candidate)
// candidates: array of lowercase candidate strings
// Returns a float array parallel to candidates (log10 probability each).
JNIEXPORT jfloatArray JNICALL
Java_com_termux_spectreboard_spectre_KenLmScorer_scoreAllNative(
        JNIEnv* env, jobject /*thiz*/, jstring contextStr, jobjectArray candidates) {
    if (!g_model || !g_vocab) return nullptr;

    // Walk context once to build the shared LM state.
    const char* ctx = env->GetStringUTFChars(contextStr, nullptr);
    lm::ngram::State state, out_state;
    g_model->BeginSentenceWrite(&state);
    {
        std::istringstream iss(ctx);
        std::string w;
        while (iss >> w) {
            g_model->Score(state, g_vocab->Index(w), out_state);
            state = out_state;
        }
    }
    env->ReleaseStringUTFChars(contextStr, ctx);

    // Score each candidate from the shared context state.
    jint n = env->GetArrayLength(candidates);
    jfloatArray result = env->NewFloatArray(n);
    if (!result) return nullptr;

    std::vector<jfloat> scores(static_cast<size_t>(n));
    for (jint i = 0; i < n; ++i) {
        auto jword = static_cast<jstring>(env->GetObjectArrayElement(candidates, i));
        const char* word = env->GetStringUTFChars(jword, nullptr);
        lm::ngram::State tmp;
        scores[static_cast<size_t>(i)] = g_model->Score(state, g_vocab->Index(word), tmp);
        env->ReleaseStringUTFChars(jword, word);
        env->DeleteLocalRef(jword);
    }
    env->SetFloatArrayRegion(result, 0, n, scores.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_termux_spectreboard_spectre_KenLmScorer_closeNative(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    g_model.reset();
    g_vocab = nullptr;
}

} // extern "C"
