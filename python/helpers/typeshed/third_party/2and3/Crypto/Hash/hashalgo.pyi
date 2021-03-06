# Stubs for Crypto.Hash.hashalgo (Python 3.5)
#
# NOTE: This dynamically typed stub was automatically generated by stubgen.

from typing import Any, Optional

class HashAlgo:
    digest_size = ...  # type: Any
    block_size = ...  # type: Any
    def __init__(self, hashFactory, data: Optional[Any] = ...) -> None: ...
    def update(self, data): ...
    def digest(self): ...
    def hexdigest(self): ...
    def copy(self): ...
    def new(self, data: Optional[Any] = ...): ...
