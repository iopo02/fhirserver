"""
JWT Authentication Module para Pipeline FHIR
----------------------------------------------
Gerencia login, renovação de tokens e re-autenticação automática
para o pipeline de ingestão com suporte a service accounts.
"""

import json
import logging
import os
import time
from typing import Optional, Dict, Any
import requests

logger = logging.getLogger("pipeline.auth")


class JwtAuthManager:
    """
    Gerencia autenticação JWT com cache de tokens e renovação automática.
    
    Suporta:
    - Login com email/password (service account)
    - Renovação automática de access token
    - Detecção de expiração e re-autenticação
    - Cache em memória com TTL
    """

    def __init__(
        self,
        auth_url: str,
        email: str,
        password: str,
        cache_file: Optional[str] = None,
    ):
        """
        Inicializa o gestor de autenticação.

        Args:
            auth_url: URL base do servidor (e.g., http://localhost:8080)
            email: Email do service account
            password: Password do service account
            cache_file: Ficheiro opcional para cache persistente de tokens
        """
        self.auth_url = auth_url.rstrip("/")
        self.email = email
        self.password = password
        self.cache_file = cache_file

        self.access_token: Optional[str] = None
        self.refresh_token: Optional[str] = None
        self.token_expiry: float = 0  # timestamp em segundos
        self.refresh_expiry: float = 0

        # Carregar tokens em cache se existir
        if cache_file and os.path.exists(cache_file):
            self._load_cache()

    def _load_cache(self) -> None:
        """Carrega tokens do ficheiro de cache."""
        try:
            with open(self.cache_file, "r") as f:
                data = json.load(f)
                self.access_token = data.get("access_token")
                self.refresh_token = data.get("refresh_token")
                self.token_expiry = data.get("token_expiry", 0)
                self.refresh_expiry = data.get("refresh_expiry", 0)
                logger.debug(f"Tokens carregados do cache: {self.cache_file}")
        except Exception as e:
            logger.warning(f"Não foi possível carregar cache de tokens: {e}")

    def _save_cache(self) -> None:
        """Guarda tokens em cache."""
        if not self.cache_file:
            return

        try:
            os.makedirs(os.path.dirname(self.cache_file) or ".", exist_ok=True)
            with open(self.cache_file, "w") as f:
                json.dump(
                    {
                        "access_token": self.access_token,
                        "refresh_token": self.refresh_token,
                        "token_expiry": self.token_expiry,
                        "refresh_expiry": self.refresh_expiry,
                    },
                    f,
                )
            logger.debug(f"Tokens guardados em cache: {self.cache_file}")
        except Exception as e:
            logger.error(f"Erro ao guardar cache de tokens: {e}")

    def _is_token_expired(self, expiry: float, buffer_seconds: int = 60) -> bool:
        """
        Verifica se um token expirou.
        
        Args:
            expiry: Timestamp de expiração (segundos)
            buffer_seconds: Segundos de margem antes da expiração real
        """
        return time.time() + buffer_seconds >= expiry

    def login(self) -> bool:
        """
        Faz login e obtém tokens access + refresh.
        
        Returns:
            True se login bem-sucedido, False caso contrário
        """
        try:
            response = requests.post(
                f"{self.auth_url}/auth/login",
                json={"email_address": self.email, "password": self.password},
                headers={"Content-Type": "application/json"},
                timeout=10,
            )

            if response.status_code not in (200, 201):
                logger.error(
                    f"Login falhou (HTTP {response.status_code}): {response.text}"
                )
                return False

            data = response.json()
            self.access_token = data.get("access_token")
            self.refresh_token = data.get("refresh_token")
            
            # Calcular expiração com base no exp claim do JWT
            # Access token tem TTL de ~15 min, refresh ~7 dias
            self.token_expiry = time.time() + 900  # 15 minutos (conservador)
            self.refresh_expiry = time.time() + 604800  # 7 dias

            logger.info(f"✓ Login bem-sucedido para {self.email}")
            self._save_cache()
            return True

        except requests.exceptions.RequestException as e:
            logger.error(f"Erro de conexão no login: {e}")
            return False
        except (json.JSONDecodeError, KeyError) as e:
            logger.error(f"Resposta de login inválida: {e}")
            return False

    def refresh(self) -> bool:
        """
        Renova o access token usando o refresh token.
        
        Returns:
            True se renovação bem-sucedida, False caso contrário
        """
        if not self.refresh_token:
            logger.warning("Sem refresh token disponível para renovação")
            return False

        try:
            response = requests.post(
                f"{self.auth_url}/auth/refresh",
                json={"refresh_token": self.refresh_token},
                headers={"Content-Type": "application/json"},
                timeout=10,
            )

            if response.status_code not in (200, 201):
                logger.warning(
                    f"Renovação falhou (HTTP {response.status_code}), "
                    "fazendo re-login..."
                )
                return self.login()

            data = response.json()
            self.access_token = data.get("access_token")
            self.refresh_token = data.get("refresh_token")
            self.token_expiry = time.time() + 900

            logger.info("✓ Access token renovado com sucesso")
            self._save_cache()
            return True

        except requests.exceptions.RequestException as e:
            logger.error(f"Erro ao renovar token: {e}")
            # Tentar re-login se refresh falhar
            return self.login()
        except (json.JSONDecodeError, KeyError) as e:
            logger.error(f"Resposta de renovação inválida: {e}")
            return self.login()

    def get_access_token(self) -> Optional[str]:
        """
        Obtém um access token válido.
        
        - Se token expirou, tenta renovar
        - Se refresh expirou, faz login novamente
        - Retorna o token ou None se autenticação falhar
        """
        # Verificar se access token expirou
        if self._is_token_expired(self.token_expiry):
            logger.debug("Access token expirou, tentando renovar...")

            # Verificar se refresh token expirou
            if self._is_token_expired(self.refresh_expiry):
                logger.info("Refresh token expirou, fazendo login novamente...")
                if not self.login():
                    return None
            else:
                if not self.refresh():
                    return None

        return self.access_token

    def get_authorization_header(self) -> Optional[Dict[str, str]]:
        """
        Retorna o header Authorization pronto para usar em requisições.
        
        Returns:
            Dicionário com header ou None se autenticação falhar
        """
        token = self.get_access_token()
        if not token:
            return None
        return {"Authorization": f"Bearer {token}"}

    def logout(self) -> bool:
        """
        Faz logout revogando ambos os tokens.
        
        Returns:
            True se logout bem-sucedido, False caso contrário
        """
        if not self.access_token or not self.refresh_token:
            logger.warning("Tokens não disponíveis para logout")
            return False

        try:
            # Obter header com access token atual
            headers = {"Content-Type": "application/json"}
            headers.update(self.get_authorization_header() or {})

            response = requests.post(
                f"{self.auth_url}/auth/logout",
                json={"refresh_token": self.refresh_token},
                headers=headers,
                timeout=10,
            )

            if response.status_code not in (200, 201):
                logger.warning(f"Logout retornou HTTP {response.status_code}")
                # Mesmo que falhe, limpar os tokens localmente
            else:
                logger.info(f"✓ Logout bem-sucedido para {self.email}")

            # Limpar tokens localmente
            self.access_token = None
            self.refresh_token = None
            self.token_expiry = 0
            self.refresh_expiry = 0

            # Limpar cache
            if self.cache_file and os.path.exists(self.cache_file):
                os.remove(self.cache_file)

            return True

        except requests.exceptions.RequestException as e:
            logger.error(f"Erro ao fazer logout: {e}")
            # Mesmo assim, limpar tokens localmente
            self.access_token = None
            self.refresh_token = None
            return False
