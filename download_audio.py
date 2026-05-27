#
# Code from: https://github.com/apple/ml-stuttering-events-dataset/blob/main/download_audio.py
# Revised for error handling
#

"""
For each podcast episode:
* Download the raw mp3/m4a file
* Convert it to a 16k mono wav file
# Remove the original file
"""

import os
import pathlib
import subprocess

import numpy as np

import argparse
import wget

parser = argparse.ArgumentParser(description='Download raw audio files for SEP-28k or FluencyBank and convert to 16k hz mono wavs.')
parser.add_argument('--episodes', type=str, required=True,
                   help='Path to the labels csv files (e.g., SEP-28k_episodes.csv)')
parser.add_argument('--wavs', type=str, default="wavs",
                   help='Path where audio files from download_audio.py are saved')


args = parser.parse_args()
episode_uri = args.episodes
wav_dir = args.wavs

# Load episode data
table = np.loadtxt(episode_uri, dtype=str, delimiter=",")
urls = table[:,2]
n_items = len(urls)

audio_types = [".mp3", ".m4a", ".mp4"]


for i in range(n_items):
	# Get show/episode IDs
	show_abrev = table[i,-2].strip()
	ep_idx = table[i,-1].strip()
	episode_url = table[i,2].strip()

	# Check file extension
	ext = ''
	for ext in audio_types:
		if ext in episode_url:
			break

	# Ensure the base folder exists for this episode
	episode_dir = pathlib.Path(f"{wav_dir}/{show_abrev}/")
	os.makedirs(episode_dir, exist_ok=True)

	# Get file paths
	audio_path_orig = pathlib.Path(f"{episode_dir}/{ep_idx}{ext}")
	wav_path = pathlib.Path(f"{episode_dir}/{ep_idx}.wav")

	# Check if this file has already been downloaded
	if os.path.exists(wav_path):
		continue

	print("Processing", show_abrev, ep_idx)

	try:
		# Download audio
		if not os.path.exists(audio_path_orig):
			wget.download(episode_url, str(audio_path_orig))

		# Convert to 16khz mono wav
		line = f'ffmpeg -i "{audio_path_orig}" -ac 1 -ar 16000 "{wav_path}"'
		process = subprocess.Popen(line, shell=True)
		process.wait()

		# Remove original file
		if os.path.exists(audio_path_orig):
			os.remove(audio_path_orig)
	except Exception as e:
		print(f"Error processing {show_abrev} {ep_idx}: {e}")
		continue

	# # Download raw audio file. This could be parallelized.
	# if not os.path.exists(audio_path_orig):
	# 	line = f"wget -O {audio_path_orig} {episode_url}"
	# 	process = subprocess.Popen([(line)],shell=True)
	# 	process.wait()

	# # Convert to 16khz mono wav file
	# line = f"ffmpeg -i {audio_path_orig} -ac 1 -ar 16000 {wav_path}"
	# process = subprocess.Popen([(line)],shell=True)
	# process.wait()

	# Remove the original mp3/m4a file
	# os.remove(audio_path_orig)