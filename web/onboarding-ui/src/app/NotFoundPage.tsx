import { Link } from "react-router-dom";
import { useI18n } from "../i18n/i18n";
import { PageHeader } from "../shared/components";

export function NotFoundPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader
        title={t("notFound.title")}
        description={t("notFound.description")}
      />
      <Link to="/">{t("notFound.home")}</Link>
    </>
  );
}
