# Web server pdf2cad.  Build & chạy:
#   docker build -t pdf2cad .
#   docker run -d --name pdf2cad -p 5020:5020 -v pdf2cad-data:/data --restart unless-stopped pdf2cad
# Truy cập: http://<máy-chủ>:5020
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 \
    PDF2CAD_DATA=/data PDF2CAD_TEMPLATES=/app/templates PDF2CAD_LEARNED=/data/templates
# opencv-headless / onnxruntime cần các thư viện hệ thống này
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY templates ./templates
RUN pip install ".[web]" && useradd -m -u 1000 app && mkdir -p /data && chown app /data
USER app

EXPOSE 5020
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:5020/api/health')"
CMD ["pdf2cad", "serve", "--host", "0.0.0.0", "--port", "5020"]
