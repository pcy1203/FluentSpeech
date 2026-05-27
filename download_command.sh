# Dataset download commands
git clone https://github.com/apple/ml-stuttering-events-dataset
cd ml-stuttering-events-dataset
pip install -r requirements.txt

python download_audio.py --episodes SEP-28k_episodes.csv --wavs ./wavs
python extract_clips.py --labels SEP-28k_labels.csv --wavs ./wavs --clips ./clips