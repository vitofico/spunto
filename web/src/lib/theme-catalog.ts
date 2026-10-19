// Generated from web/tokens.json; run pnpm generate:tokens.
export const themeCatalog = [
  {
    "id": "carta",
    "name": "Carta",
    "description": "Warm paper, a rust accent."
  },
  {
    "id": "ardesia",
    "name": "Ardesia",
    "description": "Cool slate, a blue accent."
  },
  {
    "id": "salvia",
    "name": "Salvia",
    "description": "Sage paper, a green accent."
  },
  {
    "id": "corallo",
    "name": "Corallo",
    "description": "Coral and rose, with deep wine ink."
  },
  {
    "id": "elettrica",
    "name": "Elettrica",
    "description": "Electric blue and violet, with lavender light."
  }
] as const;
export type BuiltinTheme = (typeof themeCatalog)[number]["id"];
export type Theme = string;
