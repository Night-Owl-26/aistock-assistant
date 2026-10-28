# Whisper speech-to-text for Follower, on the server's own CPU (no GPU, no API key, nothing leaves the server).
# Uses OpenAI's Whisper models through faster-whisper (the same models, 4x faster on CPU with 8-bit weights).
FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 HF_HOME=/models
RUN pip install --no-cache-dir "faster-whisper==1.1.1" "numpy<2.3" requests
WORKDIR /app
COPY app.py .
EXPOSE 9000
HEALTHCHECK --interval=30s --timeout=5s --start-period=900s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9000/health', timeout=4)"
CMD ["python", "app.py"]
