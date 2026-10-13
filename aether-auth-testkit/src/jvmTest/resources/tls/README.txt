Public test-only RSA key and self-signed localhost certificate. Never use for deployment.
Generated with openssl req -x509 -newkey rsa:2048 -nodes -days 36500 -subj /CN=localhost -addext subjectAltName=DNS:localhost.
