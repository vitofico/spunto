/** English permission sentence after "It will"; other languages keep complete sentences. */
export function willSentence(sentence: string): string {
  if (sentence.startsWith("Never "))
    return "Never " + willSentence(sentence.slice(6));
  const verb = sentence.split(" ", 1)[0];
  const base =
    verb.length < 4 || !verb.endsWith("s") || verb.endsWith("ss")
      ? verb
      : /(ch|sh|x|ss)es$/.test(verb)
        ? verb.slice(0, -2)
        : verb.slice(0, -1);
  return base + sentence.slice(verb.length);
}
