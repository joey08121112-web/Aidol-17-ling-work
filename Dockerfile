FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY web ./web
COPY run.py .
RUN useradd -m aidol && mkdir /app/data && chown -R aidol:aidol /app
USER aidol
ENV HOST=0.0.0.0 PORT=8000 DATABASE_PATH=/app/data/aidol.sqlite3
EXPOSE 8000
CMD ["python", "run.py"]
