FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY run.py .
RUN useradd -m aidol && mkdir /app/data && chown -R aidol:aidol /app
USER aidol
ENV HOST=0.0.0.0 PORT=8108 AIDOL_DATA_DIR=/app/data/hosted
EXPOSE 8108
CMD ["python", "run.py"]
