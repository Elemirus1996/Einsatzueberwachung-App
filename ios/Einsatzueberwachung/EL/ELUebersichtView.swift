import AudioToolbox
import SwiftUI

/// Einsatzleitung mit Reitern unten wie `/el` im Browser: Lage, Vermisst, Karte, Funk.
/// Die Live-Verbindung läuft, solange einer der Reiter sichtbar ist.
struct ELHauptView: View {
    @ObservedObject var ansicht: ELAnsicht
    @ObservedObject var ortung: TeamOrtung
    @ObservedObject var betrieb: ELBetrieb
    let karte: ELKartenAnsicht
    let vermisste: ELVermissteAnsicht
    @State private var reiter = Reiter.lage
    @Environment(\.scenePhase) private var phase

    enum Reiter: Hashable { case lage, vermisst, karte, funk }

    var body: some View {
        TabView(selection: $reiter) {
            NavigationStack {
                ELUebersichtView(ansicht: ansicht, ortung: ortung, betrieb: betrieb, zeigeFunk: { reiter = .funk })
            }
            .tabItem { Label("Lage", systemImage: "dot.radiowaves.left.and.right") }
            .tag(Reiter.lage)
            NavigationStack { ELVermissteView(vermisste: vermisste, betrieb: betrieb) }
                .tabItem { Label("Vermisst", systemImage: "person.fill.questionmark") }
                .tag(Reiter.vermisst)
            NavigationStack { ELKarteView(karte: karte, teams: ansicht.lage?.teams ?? [], betrieb: betrieb) }
                .tabItem { Label("Karte", systemImage: "map") }
                .tag(Reiter.karte)
            NavigationStack { ELFunkView(betrieb: betrieb, teams: ansicht.lage?.teams ?? []) }
                .tabItem { Label("Funk", systemImage: "text.bubble") }
                .tag(Reiter.funk)
        }
        .tint(ELStil.aktion)
        .onAppear {
            ansicht.beiWarnung = { stufe in
                // Warnton je Einstellung; Stufe 2 lässt sich nicht abschalten
                Toene.geraet.spielen(stufe >= 2 ? .warnung2 : .warnung1)
                UINotificationFeedbackGenerator().notificationOccurred(stufe >= 2 ? .error : .warning)
            }
            ansicht.starten()
            Task { await betrieb.notizenLaden() }
        }
        .onDisappear { ansicht.anhalten() }
        .onChange(of: phase) { _, neu in
            if neu == .active { ansicht.starten() } else if neu == .background { ansicht.anhalten() }
        }
    }
}

/// Lage der Einsatzleitung (Nachbau von `/el`): Kopfleiste mit Uhr, letzter Funkspruch, Teams nach Status
/// mit Suchzeit, Warnbalken und Bedienknöpfen. iPhone: eine Spalte; iPad: Raster.
struct ELUebersichtView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject var ansicht: ELAnsicht
    @ObservedObject var ortung: TeamOrtung
    @ObservedObject var betrieb: ELBetrieb
    let zeigeFunk: () -> Void
    @State private var gewaehltesTeam: ELLage.Team?
    @State private var stoppTeam: ELLage.Team?
    @State private var startOffen = false
    @State private var beendenOffen = false
    @State private var abmeldenFragen = false
    @Environment(\.horizontalSizeClass) private var breite

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { kontext in
            VStack(spacing: 0) {
                kopfleiste(kontext.date)
                funkLaufband
                if let lage = ansicht.lage, lage.einsatz.aktiv { statusleiste(lage) }
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        hinweise(kontext.date)
                        inhalt(kontext.date)
                        fusszeile
                    }
                    .padding(16)
                    .frame(maxWidth: 1100)
                    .frame(maxWidth: .infinity)
                }
                .refreshable { await ansicht.neuLaden() }
            }
            .background(ELStil.papier.ignoresSafeArea())
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $startOffen) {
            ELEinsatzStartView(betrieb: betrieb, eigenePosition: ortung.letztePosition)
        }
        .sheet(isPresented: $beendenOffen) {
            if let lage = ansicht.lage { ELEinsatzBeendenView(betrieb: betrieb, lage: lage) }
        }
        .sheet(item: $gewaehltesTeam) { team in
            ELTeamAktionenView(team: team, betrieb: betrieb)
        }
        .confirmationDialog("Suche von \(stoppTeam?.teamName ?? "") beenden?",
                            isPresented: Binding(get: { stoppTeam != nil }, set: { if !$0 { stoppTeam = nil } }),
                            titleVisibility: .visible) {
            Button("Suche beenden", role: .destructive) {
                if let team = stoppTeam { ausfuehren(.teamStopp(team.teamId, name: team.teamName)) }
            }
        } message: {
            Text("Der Track wird gespeichert.")
        }
        .alert(betrieb.einsatzEnde?.pdf?.erstellt == false ? "Einsatz beendet, Bericht fehlt" : "Einsatz beendet",
               isPresented: Binding(get: { betrieb.einsatzEnde != nil }, set: { if !$0 { betrieb.einsatzEnde = nil } })) {
            Button("OK") { betrieb.einsatzEnde = nil }
        } message: {
            Text(betrieb.einsatzEnde?.meldung ?? "")
        }
        .confirmationDialog("Als Einsatzleitung abmelden?", isPresented: $abmeldenFragen, titleVisibility: .visible) {
            Button("Abmelden", role: .destructive) { Task { await modell.elAbmelden() } }
        } message: {
            Text("Die Ortung der Einsatzleitung endet, das Token wird gelöscht.")
        }
    }

    // MARK: Kopfleiste

    private func kopfleiste(_ jetzt: Date) -> some View {
        let einsatz = ansicht.lage?.einsatz
        return VStack(alignment: .leading, spacing: 10) {
            // Zeile 1: Stichwort als Stempel, Übung, Menü
            HStack(spacing: 8) {
                Text((einsatz?.aktiv == true ? (einsatz?.stichwort.flatMap { $0.isEmpty ? nil : $0 } ?? "Einsatz") : "Kein Einsatz").uppercased())
                    .font(ELStil.mono(13, .bold))
                    .tracking(1.5)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .foregroundStyle(ELStil.signalRot)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .overlay(RoundedRectangle(cornerRadius: 2).stroke(ELStil.signalRot, lineWidth: 1.5))
                    .layoutPriority(1)
                if einsatz?.uebung == true { ELStempel(text: "Übung", farbe: ELStil.warnung1, gefuellt: true) }
                Spacer(minLength: 4)
                Menu {
                    if einsatz?.aktiv == true {
                        Button { beendenOffen = true } label: { Label("Einsatz beenden …", systemImage: "stop.circle") }
                    } else if ansicht.lage != nil {
                        Button { startOffen = true } label: { Label("Einsatz oder Übung starten", systemImage: "play.circle") }
                    }
                    Button(role: .destructive) { abmeldenFragen = true } label: { Label("Abmelden", systemImage: "rectangle.portrait.and.arrow.right") }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(ELStil.aufAnthrazit)
                        .frame(width: 44, height: 32)
                }
                .accessibilityLabel("Weitere Aktionen")
            }
            // Zeile 2: Ort, Szenario, EL links; Uhr und Einsatzdauer rechts
            HStack(alignment: .bottom, spacing: 12) {
                if let einsatz, einsatz.aktiv {
                    Text(kopfZeile(einsatz))
                        .font(.footnote)
                        .foregroundStyle(ELStil.aufAnthrazit.opacity(0.7))
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(jetzt, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
                        .font(ELStil.mono(28, .bold))
                        .monospacedDigit()
                        .foregroundStyle(ELStil.aufAnthrazit)
                    if let beginn = einsatz?.beginn, einsatz?.aktiv == true {
                        Text("EINSATZ " + TeamAnsichtView.dauer(max(0, jetzt.timeIntervalSince(beginn))))
                            .font(ELStil.mono(11, .semibold))
                            .foregroundStyle(ELStil.aufAnthrazit.opacity(0.6))
                    }
                }
                .fixedSize()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 12)
        .background(ELStil.anthrazit.ignoresSafeArea(edges: .top))
    }

    private func kopfZeile(_ einsatz: ELLage.Einsatz) -> String {
        var teile: [String] = []
        if let ort = einsatz.ort, !ort.isEmpty { teile.append(ort) }
        if let szenario = einsatz.szenario { teile.append(ELSzenario(rawValue: szenario)?.anzeige ?? szenario) }
        if let el = einsatz.einsatzleiter, !el.isEmpty { teile.append("EL " + el) }
        return teile.joined(separator: " · ")
    }

    // MARK: Funk und Status

    @ViewBuilder
    private var funkLaufband: some View {
        if let letzte = betrieb.notizen.first(where: { $0.art == "funk" || $0.art == "notiz" }) {
            Button(action: zeigeFunk) {
                HStack(spacing: 10) {
                    Text(letzte.art == "funk" ? "FUNK" : "NOTIZ")
                        .font(ELStil.mono(11, .bold))
                        .tracking(1.5)
                        .foregroundStyle(ELStil.aufAnthrazit.opacity(0.55))
                    Text(letzte.zeit, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
                        .font(ELStil.mono(12, .semibold))
                        .foregroundStyle(ELStil.aktiv)
                    if let von = letzte.von, !von.isEmpty {
                        Text(von.uppercased()).font(ELStil.mono(12, .semibold)).foregroundStyle(ELStil.kreide).lineLimit(1)
                    }
                    Text(letzte.text).font(.subheadline).foregroundStyle(ELStil.aufAnthrazit).lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(ELStil.aufAnthrazit.opacity(0.5))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(ELStil.anthrazit.opacity(0.92))
                .overlay(alignment: .top) { Rectangle().fill(ELStil.aufAnthrazit.opacity(0.08)).frame(height: 1) }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Letzter Funkspruch: \(letzte.text). Funk öffnen")
        }
    }

    private func statusleiste(_ lage: ELLage) -> some View {
        let aktiv = lage.teams.filter { $0.suche.status == .laeuft }.count
        return VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "dot.radiowaves.left.and.right")
                Text("\(aktiv) \(aktiv == 1 ? "TEAM" : "TEAMS") AKTIV").tracking(1.5)
                Spacer()
                Circle()
                    .fill(ansicht.live ? ELStil.aktiv : (ansicht.verbindungsfehler ? ELStil.signalRot : ELStil.warnung1))
                    .frame(width: 8, height: 8)
                Text(ansicht.live ? "LIVE" : (ansicht.verbindungsfehler ? "OFFLINE" : "VERBINDE"))
                    .font(ELStil.mono(11, .semibold))
            }
            .font(ELStil.mono(13, .semibold))
            .foregroundStyle(ELStil.vermerk)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(ELStil.platteTief)
            if let wetter = lage.wetter {
                HStack(spacing: 16) {
                    if let t = wetter.temperatur {
                        Label { Text(String(format: "%.1f °C", t)) } icon: { Image(systemName: "thermometer.medium") }
                    }
                    if let b = wetter.beschreibung, !b.isEmpty { Text(b.uppercased()) }
                    if let w = wetter.wind {
                        Label { Text(String(format: "%.0f km/h", w)) } icon: { Image(systemName: "wind") }
                    }
                    Spacer(minLength: 0)
                }
                .font(ELStil.mono(12, .medium))
                .foregroundStyle(ELStil.vermerk)
                .lineLimit(1)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(ELStil.platte)
            }
            Rectangle().fill(ELStil.rahmen).frame(height: 1)
        }
    }

    // MARK: Hinweise und Inhalt

    @ViewBuilder
    private func hinweise(_ jetzt: Date) -> some View {
        if let offen = betrieb.offen.first {
            hinweis("„\(offen.beschreibung)“ wartet auf Netz", symbol: "antenna.radiowaves.left.and.right.slash", farbe: ELStil.warnung1)
        } else if let h = betrieb.hinweis, jetzt.timeIntervalSince(h.zeit) < 6 {
            hinweis(h.text, symbol: h.fehler ? "exclamationmark.circle" : "checkmark.circle", farbe: h.fehler ? ELStil.signalRot : ELStil.aktiv)
        }
        if let warnung = ansicht.warnung, jetzt.timeIntervalSince(warnung.zeit) < 30 {
            hinweis(warnung.text, symbol: "exclamationmark.triangle.fill", farbe: warnung.stufe >= 2 ? ELStil.warnung2 : ELStil.warnung1, stark: true)
        }
        if let punkt = ansicht.teamPunkt, jetzt.timeIntervalSince(punkt.zeit) < 30 {
            hinweis(punkt.text, symbol: "mappin.and.ellipse", farbe: ELStil.warnung1, stark: true)
        }
        if let verlaengerung = modell.elVerlaengerung { ELAblaufHinweis(verlaengerung: verlaengerung) }
    }

    private func hinweis(_ text: String, symbol: String, farbe: Color, stark: Bool = false) -> some View {
        Label(text, systemImage: symbol)
            .font(stark ? .headline : .subheadline.weight(.medium))
            .foregroundStyle(farbe)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(farbe.opacity(0.1), in: RoundedRectangle(cornerRadius: ELStil.radius))
            .overlay(RoundedRectangle(cornerRadius: ELStil.radius).stroke(farbe.opacity(0.6), lineWidth: 1))
    }

    @ViewBuilder
    private func inhalt(_ jetzt: Date) -> some View {
        if let lage = ansicht.lage {
            if lage.einsatz.aktiv {
                if lage.teams.isEmpty {
                    Text("Noch keine Teams im Einsatz. Teams legt die Einsatzleitung im Monitor an.")
                        .font(.subheadline).foregroundStyle(ELStil.vermerk)
                }
                gruppe("Aktiv", lage.teams.filter { $0.suche.status == .laeuft }, jetzt)
                gruppe("Pause", lage.teams.filter { $0.suche.status == .kurzpause }, jetzt)
                gruppe("Bereit", lage.teams.filter { $0.suche.status == .bereit }, jetzt)
                gruppe("Beendet", lage.teams.filter { $0.suche.status == .beendet }, jetzt)
                Button { beendenOffen = true } label: {
                    Label("Einsatz beenden", systemImage: "stop.circle")
                        .font(ELStil.mono(14, .bold))
                        .tracking(1.5)
                        .textCase(.uppercase)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .foregroundStyle(ELStil.aktion)
                        .background(ELStil.aktion.opacity(0.06), in: RoundedRectangle(cornerRadius: ELStil.radius))
                        .overlay(RoundedRectangle(cornerRadius: ELStil.radius).stroke(ELStil.aktion.opacity(0.6), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            } else {
                keinEinsatz
            }
        } else {
            Text(ansicht.verbindungsfehler ? "Keine Verbindung. Ist WireGuard eingeschaltet?" : "Lade …")
                .font(ELStil.mono(14)).foregroundStyle(ELStil.vermerk)
        }
    }

    private var keinEinsatz: some View {
        VStack(alignment: .leading, spacing: 12) {
            ELAbschnittTitel(titel: "Lage")
            Text("Kein Einsatz aktiv").font(.title2.bold()).foregroundStyle(ELStil.inschrift)
            Text("Die Ortung der Einsatzleitung ist aus und startet, sobald ein Einsatz läuft.")
                .font(.subheadline).foregroundStyle(ELStil.vermerk)
            Button { startOffen = true } label: {
                Label("Einsatz oder Übung starten", systemImage: "play.fill")
                    .font(ELStil.mono(14, .bold))
                    .tracking(1)
                    .textCase(.uppercase)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .foregroundStyle(ELStil.aufAnthrazit)
                    .background(ELStil.aktion, in: RoundedRectangle(cornerRadius: ELStil.radius))
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .background(ELStil.platte, in: RoundedRectangle(cornerRadius: ELStil.radius))
        .overlay(RoundedRectangle(cornerRadius: ELStil.radius).stroke(ELStil.rahmen, lineWidth: 1))
    }

    @ViewBuilder
    private func gruppe(_ titel: String, _ teams: [ELLage.Team], _ jetzt: Date) -> some View {
        if !teams.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                ELAbschnittTitel(titel: titel, anzahl: teams.count)
                LazyVGrid(columns: spalten, spacing: 12) {
                    ForEach(teams) { team in
                        ELTeamKarte(team: team, ansicht: ansicht, zeitpunkt: jetzt,
                                    details: { gewaehltesTeam = team },
                                    pause: { ausfuehren(.teamPause(team.teamId, name: team.teamName)) },
                                    weiter: { ausfuehren(.teamWeiter(team.teamId, name: team.teamName)) },
                                    stopp: { stoppTeam = team })
                    }
                }
            }
        }
    }

    private var spalten: [GridItem] {
        breite == .regular ? [GridItem(.adaptive(minimum: 340), spacing: 12)] : [GridItem(.flexible())]
    }

    private func ausfuehren(_ aktion: ELAktion) {
        Task { await betrieb.ausfuehren(aktion) }
    }

    private var fusszeile: some View {
        HStack(spacing: 8) {
            Image(systemName: ortung.zustand == .ortet ? "location.fill" : "location.slash")
            Text(ortung.zustand == .ortet ? "EL-Standort wird gesendet" : "EL-Standort aus")
            Spacer()
        }
        .font(ELStil.mono(11))
        .foregroundStyle(ELStil.randnotiz)
    }
}

/// Karte eines Teams wie in `/el`: Name, Suchzeit, Hundeführer und Hund, Warnbalken bis Stufe 2, Geräte und Bedienknöpfe.
struct ELTeamKarte: View {
    let team: ELLage.Team
    @ObservedObject var ansicht: ELAnsicht
    let zeitpunkt: Date
    let details: () -> Void
    let pause: () -> Void
    let weiter: () -> Void
    let stopp: () -> Void

    private var sucht: Bool { team.suche.status == .laeuft || team.suche.status == .kurzpause }
    private var dauer: TimeInterval? { team.suche.status == .bereit ? nil : ansicht.suchdauer(team, um: zeitpunkt) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if !sucht { Circle().fill(statusFarbe).frame(width: 8, height: 8).alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 } }
                Text(team.teamName)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(ELStil.inschrift)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let dauer {
                    Text(TeamAnsichtView.dauer(dauer))
                        .font(ELStil.mono(22, .semibold))
                        .monospacedDigit()
                        .foregroundStyle(zeitFarbe)
                }
            }
            personen
            // Nur wenn er etwas aussagt: Suche läuft oder es ist schon Suchzeit vergangen
            if sucht, let w2 = team.warnung2Min, w2 > 0, let dauer, dauer > 0 || team.suche.status == .laeuft {
                ELWarnbalken(dauer: dauer, stufe1Min: team.warnung1Min, stufe2Min: w2, farbe: zeitFarbe)
            }
            geraete
            if let bis = team.pause?.erforderlichBis, bis > zeitpunkt {
                Label { Text("PAUSE BIS ") + Text(bis, style: .time) } icon: { Image(systemName: "cup.and.saucer") }
                    .font(ELStil.mono(11, .semibold)).foregroundStyle(ELStil.warnung1)
            }
            HStack(spacing: 8) {
                ELStempel(text: statusText, farbe: statusFarbe)
                Spacer(minLength: 0)
                bedienung
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(hintergrund, in: RoundedRectangle(cornerRadius: ELStil.radius))
        .overlay(RoundedRectangle(cornerRadius: ELStil.radius).stroke(randFarbe, lineWidth: team.suche.status == .laeuft ? 1.5 : 1))
        .contentShape(Rectangle())
        .onTapGesture(perform: details)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var personen: some View {
        let hf = team.hundefuehrerName.flatMap { $0.isEmpty ? nil : $0 }
        let hund = team.dogName.flatMap { $0.isEmpty ? nil : $0 }
        if hf != nil || hund != nil {
            HStack(spacing: 14) {
                if let hf { Label(hf, systemImage: "person.fill") }
                if let hund { Label(hund, systemImage: "pawprint.fill") }
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(ELStil.vermerk)
            .lineLimit(1)
            .labelStyle(.titleAndIcon)
        }
    }

    @ViewBuilder
    private var geraete: some View {
        let teile = [team.suchgebiet?.name.map { ("square.dashed", $0) },
                     team.halsband.map { ("dot.radiowaves.left.and.right", geraeteText($0)) },
                     team.handy.map { ("iphone", geraeteText($0)) }].compactMap { $0 }
        if !teile.isEmpty {
            HStack(spacing: 12) {
                ForEach(teile, id: \.0) { teil in Label(teil.1, systemImage: teil.0) }
            }
            .font(ELStil.mono(11))
            .foregroundStyle(ELStil.randnotiz)
            .lineLimit(1)
        }
    }

    @ViewBuilder
    private var bedienung: some View {
        switch team.suche.status {
        case .laeuft:
            ELTaste(symbol: "pause.fill", titel: "Pause", aktion: pause)
            ELTaste(symbol: "stop.fill", titel: "Suche beenden", farbe: ELStil.aktion, aktion: stopp)
            ELTaste(symbol: "ellipsis", titel: "Weitere Aktionen", aktion: details)
        case .kurzpause:
            ELTaste(symbol: "play.fill", titel: "Weiter", farbe: ELStil.aktiv, aktion: weiter)
            ELTaste(symbol: "stop.fill", titel: "Suche beenden", farbe: ELStil.aktion, aktion: stopp)
            ELTaste(symbol: "ellipsis", titel: "Weitere Aktionen", aktion: details)
        case .bereit, .beendet:
            Button(action: details) {
                Label(team.suche.status == .bereit ? "Start" : "Aktionen", systemImage: team.suche.status == .bereit ? "play.fill" : "ellipsis")
                    .font(ELStil.mono(13, .bold))
                    .tracking(1.2)
                    .textCase(.uppercase)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 48)
                    .foregroundStyle(team.suche.status == .bereit ? ELStil.aktiv : ELStil.inschrift)
                    .background(ELStil.platteTief, in: RoundedRectangle(cornerRadius: 3))
                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(ELStil.rahmenStark, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
    }

    private var statusText: String {
        switch team.suche.status {
        case .bereit: "Bereit"
        case .laeuft: "Aktiv"
        case .kurzpause: "Pause"
        case .beendet: "Beendet"
        }
    }

    private var statusFarbe: Color {
        switch team.suche.status {
        case .laeuft: ELStil.aktiv
        case .kurzpause: ELStil.pause
        case .bereit: ELStil.bereit
        case .beendet: ELStil.randnotiz
        }
    }

    private var zeitFarbe: Color {
        switch team.warnstufe ?? 0 {
        case 2: ELStil.warnung2
        case 1: ELStil.warnung1
        default: team.suche.status == .laeuft ? ELStil.aktiv : ELStil.vermerk
        }
    }

    private var hintergrund: Color {
        switch team.warnstufe ?? 0 {
        case 2: ELStil.warnung2.opacity(0.10)
        case 1: ELStil.warnung1.opacity(0.10)
        default: team.suche.status == .laeuft ? ELStil.aktiv.opacity(0.07) : ELStil.platte
        }
    }

    private var randFarbe: Color {
        switch team.warnstufe ?? 0 {
        case 2: ELStil.warnung2
        case 1: ELStil.warnung1
        default: team.suche.status == .laeuft ? ELStil.aktiv.opacity(0.7) : ELStil.rahmen
        }
    }

    private func geraeteText(_ g: ELLage.Team.Geraet) -> String {
        var teile: [String] = []
        if let alter = ansicht.alter(g.zeit, um: zeitpunkt) {
            teile.append(alter < 120 ? "\(Int(alter)) s" : "\(Int(alter / 60)) min")
        }
        if let akku = g.akku { teile.append("\(akku) %") }
        return teile.isEmpty ? (g.name ?? "–") : teile.joined(separator: " · ")
    }
}

/// Fortschritt der Suchzeit bis zur zweiten Warnstufe mit Markierungen „STUFE 1“ und „STUFE 2“ wie in `/el`
struct ELWarnbalken: View {
    let dauer: TimeInterval
    let stufe1Min: Int?
    let stufe2Min: Int
    let farbe: Color

    var body: some View {
        // Etwas Luft hinter Stufe 2, damit die Markierung nicht am Rand klebt
        let ende = Double(stufe2Min * 60) * 1.08
        VStack(spacing: 3) {
            GeometryReader { geo in
                ZStack(alignment: .topLeading) {
                    if let s1 = stufe1Min, s1 > 0 { marke("STUFE 1", anteil: Double(s1 * 60) / ende, breite: geo.size.width) }
                    marke("STUFE 2", anteil: Double(stufe2Min * 60) / ende, breite: geo.size.width)
                }
            }
            .frame(height: 11)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(ELStil.platteTief)
                    Rectangle().fill(farbe.opacity(0.55)).frame(width: geo.size.width * min(1, dauer / ende))
                    if let s1 = stufe1Min, s1 > 0 { strich(ELStil.warnung1, anteil: Double(s1 * 60) / ende, breite: geo.size.width) }
                    strich(ELStil.warnung2, anteil: Double(stufe2Min * 60) / ende, breite: geo.size.width)
                }
                .clipShape(RoundedRectangle(cornerRadius: 1))
                .overlay(RoundedRectangle(cornerRadius: 1).stroke(ELStil.rahmen, lineWidth: 1))
            }
            .frame(height: 10)
        }
        .accessibilityElement()
        .accessibilityLabel("Suchzeit \(Int(dauer / 60)) von \(stufe2Min) Minuten bis Warnstufe 2")
    }

    /// Beschriftung endet bündig über der Markierung
    private func marke(_ text: String, anteil: Double, breite: CGFloat) -> some View {
        Text(text)
            .font(ELStil.mono(8.5, .semibold))
            .tracking(1)
            .foregroundStyle(ELStil.randnotiz)
            .fixedSize()
            .frame(width: 60, alignment: .trailing)
            .offset(x: max(0, breite * anteil - 60 + 1))
    }

    private func strich(_ farbe: Color, anteil: Double, breite: CGFloat) -> some View {
        Rectangle().fill(farbe).frame(width: 2).offset(x: breite * anteil - 1)
    }
}

/// „Anmeldung läuft um HH:mm ab“, wenn der Server das Verlängern abgelehnt hat (#141)
struct ELAblaufHinweis: View {
    @ObservedObject var verlaengerung: ELVerlaengerung

    var body: some View {
        if let text = verlaengerung.hinweis {
            Label(text, systemImage: "clock.badge.exclamationmark").foregroundStyle(ELStil.warnung1)
        }
    }
}
