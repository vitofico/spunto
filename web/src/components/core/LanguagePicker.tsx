import { languages, setLanguage, t, useLanguage } from "../../lib/i18n";
export function LanguagePicker() {
  const language = useLanguage();
  return (
    <label className="language-picker">
      {t("language.label")}
      <select
        className="field"
        value={language}
        onChange={(event) => setLanguage(event.target.value)}
      >
        {languages.map((choice) => (
          <option key={choice.code} value={choice.code} lang={choice.code}>
            {choice.nativeName}
          </option>
        ))}
      </select>
    </label>
  );
}
