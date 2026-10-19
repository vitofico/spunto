class OperatorError(Exception):
    """Something the person running the server must fix.

    The message is shown as it is, so it is one or two plain sentences that name
    the setting, file or command involved.
    """
