import SwiftUI

/// Funk und Notizen als Chat (Gestaltung Punkt 9): Teams links, Einsatzleitung rechts in Staffelrot, Status mittig,
/// Notizen gelb. Neueste unten, Eingabe mit Team-Chips unten fest. Antworten und Löschen über langes Drücken.
struct ELFunkView: View {
    @ObservedObject var betrieb: ELBetrieb
    let teams: [ELLage.Team]
    var lage: ELLage?
    /// Im iPad-Leitstand ohne eigenen Kopf
    var mitKopf = true
    @State private var text = ""
    @State private var istFunk = true
    @State private var teamId: String?
    @State private var antwortAuf: ELNotiz?
    @FocusState private var eingabeAktiv: Bool

    var body: some View {
        VStack(spacing: 0) {
            if mitKopf { ELReiterKopf(titel: "Funk", lage: lage) }
            ScrollViewReader { leser in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if betrieb.weitereNotizen, !betrieb.notizen.isEmpty {
                            Button("Ältere laden") { Task { await betrieb.aeltereNotizenLaden() } }
                                .font(.footnote)
                                .padding(.top, 8)
                        }
                        ForEach(betrieb.notizen.reversed()) { notiz in
                            eintrag(notiz).id(notiz.id)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                }
                .defaultScrollAnchor(.bottom)
                .onChange(of: betrieb.notizen.first?.id) { _, neu in
                    if let neu { withAnimation { leser.scrollTo(neu, anchor: .bottom) } }
                }
            }
            eingabe
        }
        .background(ELStil.papier.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await betrieb.notizenLaden() }
    }

    // MARK: Einträge

    @ViewBuilder
    private func eintrag(_ notiz: ELNotiz) -> some View {
        switch notiz.art {
        case "status", "system":
            Text("\(notiz.von.map { "\($0): " } ?? "")\(notiz.text) · \(notiz.zeit.formatted(date: .omitted, time: .shortened))")
                .font(.caption)
                .foregroundStyle(ELStil.vermerk)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(ELStil.platteTief, in: Capsule())
                .frame(maxWidth: .infinity)
        case "funk":
            blase(notiz, eigene: Self.vonLeitung(notiz))
        default:
            VStack(alignment: .leading, spacing: 3) {
                Label("Notiz · \(notiz.zeit.formatted(date: .omitted, time: .shortened))", systemImage: "note.text")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(ELStil.warnung1)
                Text(notiz.text).foregroundStyle(ELStil.inschrift)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(ELStil.warnung1.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .contextMenu { menue(notiz) }
        }
    }

    private func blase(_ notiz: ELNotiz, eigene: Bool) -> some View {
        HStack {
            if eigene { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 3) {
                Text("\(absender(notiz)) · \(notiz.zeit.formatted(date: .omitted, time: .shortened))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(eigene ? ELStil.aktion : ELStil.vermerk)
                Text(notiz.text).foregroundStyle(ELStil.inschrift)
                ForEach(notiz.antworten ?? []) { a in
                    Text("↳ \(a.text)").font(.subheadline).foregroundStyle(ELStil.vermerk)
                }
            }
            .padding(10)
            .background(eigene ? ELStil.aktion.opacity(0.13) : ELStil.platte,
                        in: UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: eigene ? 14 : 4,
                                                   bottomTrailingRadius: eigene ? 4 : 14, topTrailingRadius: 14))
            .shadow(color: eigene ? .clear : ELStil.schatten, radius: 2, y: 1)
            .contextMenu { menue(notiz) }
            if !eigene { Spacer(minLength: 48) }
        }
    }

    @ViewBuilder
    private func menue(_ notiz: ELNotiz) -> some View {
        if notiz.art == "funk" {
            Button { antwortAuf = notiz; istFunk = true; eingabeAktiv = true } label: { Label("Antworten", systemImage: "arrowshape.turn.up.left") }
        }
        if notiz.loeschbar == true {
            Button(role: .destructive) { Task { await betrieb.ausfuehren(.notizLoeschen(notiz.id)) } } label: { Label("Löschen", systemImage: "trash") }
        }
    }

    /// Eigene Einträge stehen rechts: von der Einsatzleitung oder löschbar (nur EL-Notizen sind löschbar)
    static func vonLeitung(_ notiz: ELNotiz) -> Bool {
        notiz.loeschbar == true || (notiz.von ?? "").localizedCaseInsensitiveContains("einsatzleitung")
    }

    private func absender(_ notiz: ELNotiz) -> String {
        if let team = teams.first(where: { $0.teamId == notiz.teamId }), !Self.vonLeitung(notiz) { return team.teamName }
        if Self.vonLeitung(notiz), let team = teams.first(where: { $0.teamId == notiz.teamId }) { return "An \(team.teamName)" }
        return notiz.von ?? "Funk"
    }

    // MARK: Eingabe

    private var eingabe: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let antwortAuf {
                HStack {
                    Label("Antwort auf: \(antwortAuf.text)", systemImage: "arrowshape.turn.up.left").font(.caption).lineLimit(1)
                    Spacer()
                    Button { self.antwortAuf = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .accessibilityLabel("Antwort abbrechen")
                }
                .foregroundStyle(ELStil.vermerk)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        chip("Alle", gewaehlt: teamId == nil) { teamId = nil }
                        ForEach(teams) { team in
                            chip(team.teamName, gewaehlt: teamId == team.teamId) { teamId = team.teamId }
                        }
                    }
                }
            }
            HStack(spacing: 8) {
                Menu {
                    Button { istFunk = true } label: { Label("Funkspruch", systemImage: "bubble.left") }
                    Button { istFunk = false } label: { Label("Notiz", systemImage: "note.text") }
                } label: {
                    Image(systemName: istFunk ? "bubble.left.fill" : "note.text")
                        .font(.system(size: 18))
                        .foregroundStyle(istFunk ? ELStil.aktion : ELStil.warnung1)
                        .frame(width: 40, height: 40)
                        .background(ELStil.platteTief, in: Circle())
                }
                .accessibilityLabel(istFunk ? "Art: Funkspruch" : "Art: Notiz")
                TextField(istFunk ? "Funkspruch" : "Notiz", text: $text, axis: .vertical)
                    .lineLimit(1...4)
                    .focused($eingabeAktiv)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(ELStil.platteTief, in: RoundedRectangle(cornerRadius: 20))
                Button { Task { await eintragen() } } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 17))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(leer ? ELStil.randnotiz : ELStil.aktion, in: Circle())
                }
                .disabled(leer)
                .accessibilityLabel("Senden")
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(ELStil.platte)
        .overlay(alignment: .top) { Rectangle().fill(ELStil.rahmen).frame(height: 1) }
    }

    private var leer: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func chip(_ titel: String, gewaehlt: Bool, aktion: @escaping () -> Void) -> some View {
        Button(action: aktion) {
            Text(titel)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .foregroundStyle(gewaehlt ? .white : ELStil.inschrift)
                .background(gewaehlt ? ELStil.aktion : ELStil.platteTief, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func eintragen() async {
        let inhalt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        text = ""
        if istFunk {
            await betrieb.ausfuehren(.funk(inhalt, teamId: antwortAuf?.teamId ?? teamId, antwortAuf: antwortAuf?.id))
        } else {
            await betrieb.ausfuehren(.notiz(inhalt, teamId: teamId))
        }
        antwortAuf = nil
        await betrieb.notizenLaden()
    }
}
