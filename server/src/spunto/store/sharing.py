"""A trusted note service borrows exactly one already-authorized transaction."""

from contextlib import asynccontextmanager


class SharedEditStore:
    def __init__(self, store, connection, note_id):
        self.store, self.connection, self.shared_note_id = store, connection, note_id

    def __getattr__(self, name):
        return getattr(self.store, name)

    @asynccontextmanager
    async def read(self):
        yield self.connection

    @asynccontextmanager
    async def write(self):
        yield self.connection
