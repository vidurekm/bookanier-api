# Bookanear: ověřený průvodce funkcemi

Tento soubor je produktová znalostní báze asistenta. Popisuje jen ověřené chování
uživatelského rozhraní a API. Pokud odpověď není v tomto průvodci ani v systémových
instrukcích, asistent má nejistotu přiznat a odkázat uživatele na správce organizace
nebo podporu. Nesmí si domýšlet obrazovky, tlačítka, oprávnění ani výsledky operací.

## Jak funguje administrace

- Po přihlášení se otevře pracovní prostor organizace. Přepínač v horní liště mění
  organizaci; dostupné stránky závisejí na zvoleném modelu rezervací a oprávněních.
- Nedokončenou organizaci otevře průvodce nastavením. Dokud je nastavení rozpracované,
  běžná navigace administrace se nezobrazuje.
- Administrace nabízí přehled, rezervace, pobočky, služby, tým, žádosti o volno,
  zdroje, zákazníky a nastavení. Pro událostní organizaci se zobrazuje stránka
  „Lekce a termíny“. Některé sekce jsou skryté podle modelu rezervací a oprávnění.
- Asistent neumí zjistit, jakou organizaci nebo roli má právě přihlášený uživatel.
  Má poradit obecný postup a upozornit, pokud se položka kvůli oprávnění nemusí zobrazit.

## Pobočky

- V části „Pobočky“ lze založit pobočku a upravit její název, adresu, časové pásmo,
  stav a fotografie. Ověření adresy může nabídnout výběr polohy na mapě.
- Otevřením „Spravovat“ se zobrazí záložky „Otevírací doba“, „Výjimky a svátky“
  a „Nabídka služeb“. Týdenní dobu lze rozdělit na více intervalů za den.
- Výjimka mění otevírací dobu jen pro vybraný den. Před uložením se kontrolují
  kolize s rezervacemi; rezervace se automaticky neruší.
- Služba musí být přiřazena k pobočce, aby ji bylo možné veřejně rezervovat.

## Služby a zdroje

- V části „Služby“ lze vytvořit a upravit službu. „Spravovat“ otevře její ceny
  a délky, možnosti rezervace, případné požadované zdroje a fotografie.
- Ceny a délky se ukládají jako časově platné verze. Verze uchovávají historii;
  časové intervaly cen se nesmějí překrývat. Službu nelze veřejně rezervovat,
  dokud nemá platnou verzi.
- U služby lze nastavit, zda si zákazník vybírá pracovníka. Pokud výběr není
  povolen, systém dostupné pracovníky přiřazuje automaticky. Služba může vyžadovat
  více pracovníků současně.
- Výběr zdroje zákazníkem je dostupný jen při odpovídajícím požadavku na jeden
  zdroj jednoho typu. Požadavky na zdroje se konfigurují zvlášť u služby.
- „Zdroje“ spravují například místnosti, stoly a vybavení pro vybranou pobočku.
  Typy zdrojů jsou v samostatné záložce. Zdroje používané v rezervacích nelze vždy
  odstranit; nepoužívaný zdroj lze deaktivovat nebo změnit jeho stav.

## Tým, pracovní doba a volno

- V části „Tým“ lze pracovníkovi přiřadit pobočky a služby. Detail pracovníka
  obsahuje záložky „Pobočky“, „Služby“, „Pracovní doba“ a „Volno a výjimky“.
- V detailu pracovníka se rozpracované změny ukládají tlačítkem „Uložit všechny
  změny“. Pobočky a služby se upraví výběrem; v pracovní době lze přidávat
  intervaly. Volno a výjimky se nejprve přidají mezi neuložené změny a pak se
  uloží společným tlačítkem. Rozpracované změny zůstávají při přepínání záložek
  a poboček v otevřeném detailu.
- U výjimek pracovníka se kontrolují kolize s rezervacemi. Obnovení běžného
  týdenního rozvrhu odstraní výjimku pro zvolený den. Rezervace se nikdy neruší
  automaticky kvůli změně rozvrhu.
- Některá další nastavení a samostatná dialogová okna mají vlastní tlačítka pro
  uložení; tlačítko „Uložit všechny změny“ se vztahuje na detail pracovníka.
- Zaměstnanci mohou žádat o volno nebo změnu pracovní doby v části „Žádosti o volno“.
  O schválení rozhoduje uživatel s příslušným oprávněním; možnosti se mohou lišit
  podle nastavení samo-schvalování.

## Rezervace a zákazníci

- V části „Rezervace“ lze prohlížet rezervace v seznamu nebo kalendáři, filtrovat je
  a otevřít detail. Dostupná správa rezervace závisí na oprávnění uživatele.
- Veřejné odkazy umožňují zákazníkům prohlížet dostupné služby nebo události
  a vytvořit rezervaci podle nastavení organizace.
- Veřejný rezervační proces ukazuje pouze dostupné termíny. Asistent nevidí živou
  dostupnost, konkrétní rezervace ani údaje zákazníků; nemůže rezervaci vytvořit,
  změnit, zrušit ani potvrdit.
- Stránka „Customers“ zobrazuje zákazníky vzniklé z rezervací. Asistent nemá přístup
  k jejich osobním údajům ani k účtu organizace.

## Události a nastavení

- U událostního modelu se v „Lekce a termíny“ zakládá opakující se typ lekce a
  plánují se jeho konkrétní termíny. U termínu lze zobrazit přihlášené účastníky
  a naplánovaný termín zrušit po potvrzení.
- Nastavení organizace a nastavení osobního účtu jsou oddělená. Některé volby,
  například jazyk, vzhled a formát data, se ukládají v prohlížeči nebo profilu;
  asistent je neumí změnit.

## Co asistent může a nemůže

- Asistent poskytuje pouze návod k používání Bookanearu. Odpovídá jazykem uživatele.
- Dostává zprávy aktuální konverzace, ale ne data organizace, živý stav aplikace,
  databázi, dokumenty nahrané uživatelem ani přístupové údaje. Nemůže provádět akce v aplikaci.
- Historie konverzace se ukládá k přihlášenému účtu a lze ji načíst po znovuotevření
  asistenta i po dalším přihlášení. Uživatel ji může kdykoli smazat; ukládá se nejvýše
  posledních 200 zpráv.
- Zprávy se posílají nakonfigurovanému AI poskytovateli. Uživatelé do chatu nemají
  vkládat hesla, platební údaje ani jiné citlivé informace.
- Asistent odpovídá jen k ověřeným funkcím Bookanearu. U chybějících nebo nejistých
  informací má přiznat nejistotu; nesmí slíbit funkci ani konkrétní výsledek.
