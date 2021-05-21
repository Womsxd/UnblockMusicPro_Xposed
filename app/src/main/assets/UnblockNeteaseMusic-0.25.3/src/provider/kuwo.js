const cache = require('../cache')
const insure = require('./insure')
const select = require('./select')
const crypto = require('../crypto')
const request = require('../request')

const format = song => ({
	id: song.musicrid.split('_').pop(),
	name: song.name,
	duration: song.duration * 1000,
	album: {
		id: song.albumid,
		name: song.album
	},
	artists: song.artist.split('&').map((name, index) => ({
		id: index ? null : song.artistid,
		name
	})),
	weight: 0
})

var weight = 0

const search = info => {

	const keyword = encodeURIComponent(info.keyword.replace(' - ', ' '))
	const url = `http://www.kuwo.cn/api/www/search/searchMusicBykeyWord?key=${keyword}&pn=1&rn=5`

	return request('GET', `http://kuwo.cn/search/list?key=${keyword}`)
		.then(response => response.headers['set-cookie'].find(line => line.includes('kw_token')).replace(/;.*/, '').split('=').pop())
		.then(token => request('GET', url, {
			referer: `http://www.kuwo.cn/search/list?key=${keyword}`,
			csrf: token,
			cookie: `kw_token=${token}`
		}))
		.then(response => response.json())
		.then(jsonBody => {
			const list = jsonBody.data.list.map(format)
			const matched = select.selectList(list, info)
			weight = matched.weight
			return matched ? matched.id : Promise.reject()
		})
}

const track = id => {
	const url = (crypto.kuwoapi ?
		'http://mobi.kuwo.cn/mobi.s?f=kuwo&q=' + crypto.kuwoapi.encryptQuery(
			'corp=kuwo&p2p=1&type=convert_url2&sig=0&format=' + ['flac', 'mp3'].slice(select.ENABLE_FLAC ? 0 : 1).join('|') + '&rid=' + id
		) :
		'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_' + id
	)

	//const url = 'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_' + id

	return request('GET', url, {
			'user-agent': 'okhttp/3.11.0'
		})
		.then(response => response.body())
		.then(body => {
			const url = (body.match(/http[^\s$"]+/) || [])[0]
			return url ? {
				url: url,
				weight: weight
			} : Promise.reject()
		})
		.catch(() => insure().kuwo.track(id))
}

const check = info => cache(search, info).then(track)

module.exports = {
	check,
	track
}