package com.termux.spectreboard.spectre.neural

object NeuralConfig {
    const val BEAM_WIDTH = 6
    const val MAX_LENGTH = 20
    const val CONFIDENCE_THRESHOLD = 0.01f
    const val BEAM_ALPHA = 1.4f
    const val BEAM_PRUNE_CONFIDENCE = 0.8f
    const val BEAM_SCORE_GAP = 80.0f
    const val TEMPERATURE = 1.0f
    // must equal the encoder's fixed input length (assets/models/model_config.json max_seq_len);
    // 150 made every predict() throw ORT_INVALID_ARGUMENT since 2026-07-20 (scope.md 2026-10-27)
    const val MAX_SEQ_LENGTH = 250
    const val ONNX_THREADS = 2
    const val ADAPTIVE_WIDTH_STEP = 12
    const val SCORE_GAP_STEP = 12
    const val DECODER_SEQ_LEN = 20

    const val PAD_IDX = 0L
    const val UNK_IDX = 1L
    const val SOS_IDX = 2L
    const val EOS_IDX = 3L

    const val VOCAB_SIZE = 30
    const val TRAJECTORY_FEATURES = 6

    const val ENCODER_MODEL = "models/swipe_encoder_android.onnx"
    const val DECODER_MODEL = "models/swipe_decoder_android.onnx"
    const val TOKENIZER_JSON = "models/tokenizer.json"
    const val DICTIONARY = "dictionaries/en.txt"

    const val SMOOTHING_WINDOW = 3

    // filesDir/neural/: swipe_encoder_android.onnx, swipe_decoder_android.onnx, swipe_vocab.txt
    const val OVERRIDE_DIR = "neural"
    const val PUSHED_VOCAB = "swipe_vocab.txt"
}
