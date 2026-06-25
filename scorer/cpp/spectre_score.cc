// SpectreBoard KenLM scorer — stdin/stdout IPC for Android autocorrect
//
// Protocol:
//   Input line:  space-separated words  (context words + candidate as last word)
//   Output line: log10 probability of the LAST word given the preceding context
//
// The keyboard sends:  "the quick brown"  (last word = candidate to score)
// We return the log10 P("brown" | "the", "quick")
//
// Empty input line → outputs "0\n" (neutral score, no penalty).
// Unknown words are handled by KenLM's built-in backoff.

#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <cstdlib>

#include "lm/model.hh"

int main(int argc, char* argv[]) {
    if (argc < 2) {
        std::cerr << "Usage: spectre_score <model.blm>\n";
        return 1;
    }

    lm::ngram::Config cfg;
    cfg.messages = nullptr; // suppress load noise on stderr

    lm::ngram::TrieModel model(argv[1], cfg);
    const auto& vocab = model.GetVocabulary();

    std::string line;
    while (std::getline(std::cin, line)) {
        // Tokenise
        std::vector<std::string> words;
        {
            std::istringstream iss(line);
            std::string w;
            while (iss >> w) words.push_back(w);
        }

        if (words.empty()) {
            std::cout << "0\n";
            std::cout.flush();
            continue;
        }

        // Walk the model up to the second-to-last word to build context state.
        lm::ngram::State state, out_state;
        model.BeginSentenceWrite(&state);
        for (std::size_t i = 0; i + 1 < words.size(); ++i) {
            model.Score(state, vocab.Index(words[i]), out_state);
            state = out_state;
        }

        // Score only the candidate (last word) given the accumulated context.
        float score = model.Score(state, vocab.Index(words.back()), out_state);
        std::cout << score << "\n";
        std::cout.flush();
    }

    return 0;
}
