#include "../common/arg.h"
#include "../common/common.h"
#include "beam-search/beam-search.h"
#include "llama.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static bool need_value(int i, int argc, const char * name) {
    if (i + 1 >= argc) {
        fprintf(stderr, "error: missing value for %s\n", name);
        return false;
    }
    return true;
}

static bool parse_int(const char * s, int & out, const char * name) {
    try {
        out = std::stoi(s);
        return true;
    } catch (...) {
        fprintf(stderr, "error: invalid integer for %s: %s\n", name, s);
        return false;
    }
}

static void usage(const char * argv0) {
    fprintf(stderr,
        "Usage: %s -m MODEL -p TEXT [-n TOKENS] [-t THREADS] [-sl SRC] [-tl TGT] [--beam-size N]\n",
        argv0);
}

int main(int argc, char ** argv) {
    common_params params;
    params.n_predict = 160;
    params.nllb_src_lang = "eng_Latn";
    params.nllb_tgt_lang = "vie_Latn";
    params.nllb_beam_size = 1;
    params.n_gpu_layers = 0;
    params.cpuparams.n_threads = 4;
    params.cpuparams.poll = 0;

    for (int i = 1; i < argc; ++i) {
        const char * a = argv[i];
        if (!strcmp(a, "-m") || !strcmp(a, "--model")) {
            if (!need_value(i, argc, a)) return 1;
            params.model.path = argv[++i];
        } else if (!strcmp(a, "-p") || !strcmp(a, "--prompt")) {
            if (!need_value(i, argc, a)) return 1;
            params.prompt = argv[++i];
        } else if (!strcmp(a, "-n") || !strcmp(a, "--n-predict")) {
            if (!need_value(i, argc, a)) return 1;
            if (!parse_int(argv[++i], params.n_predict, a)) return 1;
        } else if (!strcmp(a, "-t") || !strcmp(a, "--threads")) {
            if (!need_value(i, argc, a)) return 1;
            if (!parse_int(argv[++i], params.cpuparams.n_threads, a)) return 1;
        } else if (!strcmp(a, "-sl") || !strcmp(a, "--src-lang")) {
            if (!need_value(i, argc, a)) return 1;
            params.nllb_src_lang = argv[++i];
        } else if (!strcmp(a, "-tl") || !strcmp(a, "--tgt-lang")) {
            if (!need_value(i, argc, a)) return 1;
            params.nllb_tgt_lang = argv[++i];
        } else if (!strcmp(a, "--beam-size")) {
            if (!need_value(i, argc, a)) return 1;
            if (!parse_int(argv[++i], params.nllb_beam_size, a)) return 1;
        } else if (!strcmp(a, "--help") || !strcmp(a, "-h")) {
            usage(argv[0]);
            return 0;
        } else if (a[0] == '-') {
            fprintf(stderr, "error: unsupported argument: %s\n", a);
            usage(argv[0]);
            return 1;
        } else if (params.prompt.empty()) {
            params.prompt = a;
        } else {
            params.prompt += " ";
            params.prompt += a;
        }
    }

    if (params.model.path.empty() || params.prompt.empty()) {
        usage(argv[0]);
        return 1;
    }

    auto llama_init = common_init_from_params(params);
    llama_model * model = llama_init->model();
    llama_context * ctx = llama_init->context();

    if (model == nullptr || ctx == nullptr) {
        fprintf(stderr, "error: failed to initialize NLLB model/context\n");
        return 1;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    llama_token src_lang_token = LLAMA_TOKEN_NULL;
    llama_token tgt_lang_token = LLAMA_TOKEN_NULL;

    for (int i = 0; i < llama_vocab_n_tokens(vocab); ++i) {
        const char * tok = llama_vocab_get_text(vocab, i);
        if (tok == nullptr) continue;
        if (params.nllb_src_lang == tok) src_lang_token = i;
        if (params.nllb_tgt_lang == tok) tgt_lang_token = i;
        if (src_lang_token != LLAMA_TOKEN_NULL && tgt_lang_token != LLAMA_TOKEN_NULL) break;
    }

    if (src_lang_token == LLAMA_TOKEN_NULL) {
        fprintf(stderr, "error: source language token not found: %s\n", params.nllb_src_lang.c_str());
        return 1;
    }
    if (tgt_lang_token == LLAMA_TOKEN_NULL) {
        fprintf(stderr, "error: target language token not found: %s\n", params.nllb_tgt_lang.c_str());
        return 1;
    }

    std::vector<llama_token> tokens;
    tokens.push_back(src_lang_token);
    std::vector<llama_token> text_tokens = common_tokenize(vocab, params.prompt, false, true);
    tokens.insert(tokens.end(), text_tokens.begin(), text_tokens.end());
    tokens.push_back(llama_vocab_eos(vocab));

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_encode(ctx, batch) != 0) {
        fprintf(stderr, "error: llama_encode failed\n");
        return 1;
    }

    std::vector<llama_token> initial_tokens;
    initial_tokens.push_back(llama_vocab_eos(vocab));
    initial_tokens.push_back(tgt_lang_token);

    if (params.nllb_beam_size > 1) {
        llama_beam::beam_search_params bparams;
        bparams.beam_size = params.nllb_beam_size;
        bparams.max_length = params.n_predict;
        llama_beam::beam_search_engine engine(ctx, bparams);
        auto result = engine.search(initial_tokens, [vocab](llama_token t) {
            return llama_vocab_is_eog(vocab, t);
        });
        if (result.hypotheses.empty()) return 1;
        const auto & best = result.best();
        for (size_t i = 0; i < best.tokens.size(); ++i) {
            char buf[128];
            int n = llama_token_to_piece(vocab, best.tokens[i], buf, sizeof(buf), i == 0 ? 1 : 0, true);
            if (n > 0) {
                fwrite(buf, 1, n, stdout);
            }
        }
        fputc('\n', stdout);
    } else {
        batch = llama_batch_get_one(initial_tokens.data(), initial_tokens.size());
        if (llama_decode(ctx, batch) != 0) {
            fprintf(stderr, "error: initial decoder pass failed\n");
            return 1;
        }

        for (int i = 0; i < params.n_predict; ++i) {
            float * logits = llama_get_logits(ctx);
            int n_vocab = llama_vocab_n_tokens(vocab);
            llama_token best = 0;
            float best_logit = -1e30f;
            for (int j = 0; j < n_vocab; ++j) {
                if (logits[j] > best_logit) {
                    best_logit = logits[j];
                    best = j;
                }
            }
            if (llama_vocab_is_eog(vocab, best)) break;
            char buf[128];
            int n = llama_token_to_piece(vocab, best, buf, sizeof(buf), i == 0 ? 1 : 0, true);
            if (n > 0) {
                fwrite(buf, 1, n, stdout);
                fflush(stdout);
            }
            batch = llama_batch_get_one(&best, 1);
            if (llama_decode(ctx, batch) != 0) {
                fprintf(stderr, "error: decoder pass failed at token %d\n", i);
                return 1;
            }
        }
        fputc('\n', stdout);
    }

    fflush(stdout);
    return 0;
}
