#!/usr/bin/env python3
"""Pick the speech threshold from the keyboard's loudness traces.

Pull the traces from the phone first:
    adb pull /sdcard/Android/data/helium314.keyboard.whisper/files/whisper_traces traces/
    python3 tools/whisper_traces.py traces/

Each trace is one recording: loudness (RMS) every 100 ms, plus in live mode the server's
speech segments [start, end] (its own VAD). Frames inside a segment count as speech, the rest
as background. For each candidate factor (speech > factor x rolling noise floor, as in
WhisperVoice.record) it prints how much speech the keyboard would hear, how often background
would pass as speech, and how long after the last word the recording would stop.
"""
import glob, json, os, statistics, sys

WINDOW = 30          # frames of rolling minimum = noise floor (WhisperVoice.NOISE_WINDOW)
MIN_RMS = 120        # WhisperVoice.MIN_SPEECH_RMS
FACTORS = [1.2, 1.4, 1.6, 1.8, 2.0, 2.5, 3.0]


def floor_and_labels(t):
    rms = t["rms"]
    segs = t.get("segments") or []
    floor, labels = [], []
    for i, r in enumerate(rms):
        floor.append(min(rms[max(0, i - WINDOW + 1):i + 1]))
        sec = (i + 0.5) / 10
        labels.append(any(a - 0.2 <= sec <= b + 0.2 for a, b in segs))
    return rms, floor, labels


def stop_after_last_word(rms, floor, labels, factor, silence_frames):
    """Frames from the end of the last speech segment until the simulated silence stop."""
    last = max((i for i, s in enumerate(labels) if s), default=None)
    if last is None:
        return None
    quiet = 0
    for i in range(last + 1, len(rms)):
        if rms[i] > max(MIN_RMS, floor[i] * factor):
            quiet = 0
        else:
            quiet += 1
        if quiet >= silence_frames:
            return (i - last) / 10
    return None  # would not stop by itself within the recording


def main(folder):
    traces = [json.load(open(p)) for p in sorted(glob.glob(os.path.join(folder, "*.json")))]
    traces = [t for t in traces if t.get("segments")]
    if not traces:
        sys.exit("no live traces with server segments")
    print(f"{len(traces)} recordings with server speech times\n")
    for t in traces:
        rms, floor, lab = floor_and_labels(t)
        sp = [r for r, s in zip(rms, lab) if s]
        bg = [r for r, s in zip(rms, lab) if not s]
        ratio = (statistics.median(sp) / max(1, statistics.median(bg))) if sp and bg else float("nan")
        print(f"{t['at']}  {t['total_ms']/1000:5.1f} s  stop={t['reason']:<17} "
              f"background median {statistics.median(bg) if bg else 0:6.0f}  "
              f"speech median {statistics.median(sp) if sp else 0:6.0f}  ratio {ratio:4.1f}")
        st, en, lm = t.get("start") or {}, t.get("end") or {}, t.get("live_metrics") or {}
        mic = (t.get("input") or {}).get("type", "?")
        net = st.get("transport", "?") + (" -> " + en.get("transport", "?") if en.get("network_id") != st.get("network_id") else "")
        print(f"    mic {mic}, bt {','.join(st.get('bluetooth_audio', [])) or '-'}, app {st.get('app', '?')}, "
              f"net {net} up~{st.get('up_kbps', '?')} kbit/s, stream {lm.get('format', '-')} {lm.get('kbit_s', '-')} kbit/s, "
              f"connect {lm.get('connect_ms', '-')} ms, final {lm.get('final_after_end_ms', '-')} ms after end"
              + (", FALLBACK upload" if t.get("fallback_upload") else "")
              + (f", failed: {lm['failed']}" if lm.get("failed") else ""))
    print("\nfactor  speech heard  background as speech  stop after last word (median, never stops)")
    for f in FACTORS:
        hit = tot = fp = bgn = 0
        stops, never = [], 0
        for t in traces:
            rms, floor, lab = floor_and_labels(t)
            for r, fl, s in zip(rms, floor, lab):
                over = r > max(MIN_RMS, fl * f)
                if s:
                    tot += 1; hit += over
                else:
                    bgn += 1; fp += over
            st = stop_after_last_word(rms, floor, lab, f, t["silence_ms"] // 100)
            if st is None:
                never += 1
            else:
                stops.append(st)
        med = f"{statistics.median(stops):4.1f} s" if stops else "   -  "
        print(f"{f:5.1f}   {hit/max(1,tot):6.0%}        {fp/max(1,bgn):6.0%}               {med}, {never}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "traces")
